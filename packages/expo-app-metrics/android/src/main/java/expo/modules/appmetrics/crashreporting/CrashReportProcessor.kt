package expo.modules.appmetrics.crashreporting

import android.content.Context
import android.util.Log
import expo.modules.appmetrics.AppMetricsPreferences
import expo.modules.appmetrics.TAG
import expo.modules.appmetrics.storage.Session
import expo.modules.appmetrics.storage.SessionManager
import expo.modules.appmetrics.utils.TimeUtils

/** Persistence seam for the set of already-processed exit-record keys. */
interface ProcessedExitRecordsStore {
  fun getProcessedKeys(): Set<String>

  fun setProcessedKeys(keys: Set<String>)
}

/** SharedPreferences-backed store, see [AppMetricsPreferences]. */
class PreferencesProcessedExitRecordsStore(private val context: Context) : ProcessedExitRecordsStore {
  override fun getProcessedKeys(): Set<String> = AppMetricsPreferences.getProcessedExitRecordKeys(context)

  override fun setProcessedKeys(keys: Set<String>) {
    AppMetricsPreferences.setProcessedExitRecordKeys(context, keys)
  }
}

/**
 * Next-launch crash processing: turns the previous process's death evidence —
 * pending JVM crash files from [JvmCrashHandler] and OS exit records from
 * [ExitInfoProvider] — into stored crash reports keyed by session.
 *
 * Policy (each point deliberate, see the ENG-21535 plan):
 * - **Debuggable builds:** a crash file is promoted only when corroborated by a
 *   matching death record — the dev red box / dev launcher can catch an
 *   exception without the process dying, and an uncorroborated file would
 *   fabricate a crash. Uncorroborated files are discarded.
 * - **Release builds:** the file is promoted on its own evidence. There is no
 *   red box in release, and requiring corroboration would silently drop real
 *   crashes whenever the small exit-record ring buffer evicts the death record.
 * - **Dedup:** a single crash seen as both a file and an exit record yields one
 *   report — the file's (it has the stack); its record is consumed.
 * - **Attribution order:** session id stamped in the record / embedded in the
 *   file, then timestamp matching ([SessionAttributor]); never the current
 *   session.
 * - **At-least-once:** the processed-record cursor is saved only after all
 *   writes; reprocessing after a mid-run death is idempotent (insert-or-replace
 *   keyed by session).
 */
class CrashReportProcessor(
  private val sessionManager: SessionManager,
  private val crashFileWriter: CrashFileWriter,
  private val exitInfoProvider: ExitInfoProvider,
  private val processedRecordsStore: ProcessedExitRecordsStore,
  private val isDebuggableBuild: Boolean,
  private val appVersion: String?
) {
  suspend fun process(currentSessionId: String?) {
    val allRecords = exitInfoProvider.getExitRecords()
    val processedKeys = processedRecordsStore.getProcessedKeys()
    val newRecords = allRecords.filter { it.key !in processedKeys }
    val pendingFiles = crashFileWriter.listPendingCrashes()

    val failedRecordKeys = if (newRecords.isNotEmpty() || pendingFiles.isNotEmpty()) {
      processCrashes(newRecords, pendingFiles, currentSessionId)
    } else {
      emptySet()
    }

    // The cursor is exactly the keys still present in the OS buffer — records
    // that fell out can never be returned again, so the set stays bounded.
    // Saved after the writes above, minus keys whose write failed (they retry
    // next launch): at-least-once, never at-most-once.
    processedRecordsStore.setProcessedKeys(
      allRecords.map { it.key }.toSet() - failedRecordKeys
    )
  }

  /** Returns the keys of exit records whose report failed to persist. */
  private suspend fun processCrashes(
    newRecords: List<ExitRecord>,
    pendingFiles: List<PendingJvmCrash>,
    currentSessionId: String?
  ): Set<String> {
    // If session rows can't load, reports with a stamped/embedded session id
    // still store; only the timestamp fallback degrades (drops, with a log).
    val sessions = runCatching { sessionManager.getAllSessionRows() }
      .getOrElse {
        Log.e(TAG, "Failed to load sessions for crash attribution", it)
        emptyList()
      }
    val resolvedAppVersion = appVersion ?: "unknown"
    val ingestedAt = TimeUtils.getCurrentTimestampInISOFormat()
    val consumedRecordKeys = mutableSetOf<String>()
    val reportedSessionIds = mutableSetOf<String>()
    val failedRecordKeys = mutableSetOf<String>()

    for (file in pendingFiles) {
      val corroborating = newRecords.firstOrNull { record ->
        record.key !in consumedRecordKeys && record.isDeathRecord && record.matches(file)
      }
      corroborating?.let { consumedRecordKeys += it.key }

      var keepFileForRetry = false
      val sessionId = file.sessionId
        ?: corroborating?.sessionId
        ?: attributeByTimestamp(file.crashedAtMillis, sessions, currentSessionId)
      if (isDebuggableBuild && corroborating == null) {
        Log.i(TAG, "Discarding a pending crash file without a matching process death — the exception was likely caught by the dev tooling without killing the app.")
      } else if (sessionId == currentSessionId && sessionId != null) {
        // A file carrying the *current* process's session id means the process
        // didn't die after the handler ran (something downstream swallowed the
        // exception) — by definition not a crash of a finished session.
        Log.i(TAG, "Discarding a pending crash file attributed to the live session — the process survived the exception.")
      } else if (sessionId == null) {
        Log.w(TAG, "Dropping a crash report that can't be attributed to any session (crashed at ${TimeUtils.millisToTimestamp(file.crashedAtMillis)}).")
      } else {
        val stored = runCatching {
          sessionManager.setCrashReport(
            sessionId,
            file.toCrashReport(ingestedAt, resolvedAppVersion).encodeToJsonString()
          )
        }
        if (stored.isSuccess) {
          reportedSessionIds += sessionId
        } else {
          // Keep the file; the next launch retries (insert-or-replace is idempotent).
          Log.e(TAG, "Failed to persist a crash report; keeping its pending file for retry", stored.exceptionOrNull())
          keepFileForRetry = true
        }
      }
      if (!keepFileForRetry) {
        crashFileWriter.delete(file)
      }
    }

    for (record in newRecords) {
      if (record.key in consumedRecordKeys || !record.isStandaloneCrash) {
        continue
      }
      val sessionId = record.sessionId
        ?: attributeByTimestamp(record.timestampMillis, sessions, currentSessionId)
      if (sessionId == null) {
        Log.w(TAG, "Dropping an exit record (reason=${record.reason}) that can't be attributed to any session.")
        continue
      }
      if (sessionId == currentSessionId || sessionId in reportedSessionIds) {
        continue
      }
      val stored = runCatching {
        sessionManager.setCrashReport(
          sessionId,
          record.toCrashReport(ingestedAt, resolvedAppVersion).encodeToJsonString()
        )
      }
      if (stored.isSuccess) {
        reportedSessionIds += sessionId
      } else {
        Log.e(TAG, "Failed to persist a crash report from an exit record; it will retry next launch", stored.exceptionOrNull())
        failedRecordKeys += record.key
      }
    }
    return failedRecordKeys
  }

  private fun attributeByTimestamp(
    crashedAtMillis: Long,
    sessions: List<Session>,
    currentSessionId: String?
  ): String? {
    val timestamp = TimeUtils.millisToTimestamp(crashedAtMillis)
    // The current session is unfinished and intersects everything in its
    // future — exclude it so the fallback can't blame a past crash on it.
    val candidates = sessions.filter { it.id != currentSessionId }
    return SessionAttributor.findMatchingSession(timestamp, timestamp, candidates)?.id
  }
}

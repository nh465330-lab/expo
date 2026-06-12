package expo.modules.appmetrics.crashreporting

import android.app.ApplicationExitInfo
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import expo.modules.appmetrics.storage.MetricsDatabase
import expo.modules.appmetrics.storage.SessionManager
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class CrashReportProcessorTest {
  @get:Rule
  val tmp = TemporaryFolder()

  private lateinit var database: MetricsDatabase
  private lateinit var sessionManager: SessionManager
  private lateinit var crashFileWriter: CrashFileWriter

  private val exitRecords = mutableListOf<ExitRecord>()
  private val processedKeys = mutableSetOf<String>()

  private val exitInfoProvider = ExitInfoProvider { exitRecords.toList() }

  private val processedRecordsStore = object : ProcessedExitRecordsStore {
    override fun getProcessedKeys(): Set<String> = processedKeys.toSet()

    override fun setProcessedKeys(keys: Set<String>) {
      processedKeys.clear()
      processedKeys.addAll(keys)
    }
  }

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    database = Room
      .inMemoryDatabaseBuilder(context, MetricsDatabase::class.java)
      .allowMainThreadQueries()
      .build()
    sessionManager = SessionManager(context, database)
    crashFileWriter = CrashFileWriter(tmp.root).also { it.prepare() }
  }

  @After
  fun tearDown() {
    database.close()
  }

  private fun processor(isDebuggableBuild: Boolean = false): CrashReportProcessor =
    CrashReportProcessor(
      sessionManager = sessionManager,
      crashFileWriter = crashFileWriter,
      exitInfoProvider = exitInfoProvider,
      processedRecordsStore = processedRecordsStore,
      isDebuggableBuild = isDebuggableBuild,
      appVersion = "1.2.3"
    )

  private fun writeCrashFile(
    sessionId: String? = "crashed-session",
    pid: Int = 123,
    crashedAtMillis: Long = 1_700_000_000_000
  ) {
    crashFileWriter.write(
      throwable = IllegalStateException("boom"),
      threadName = "main",
      sessionId = sessionId,
      pid = pid,
      crashedAtMillis = crashedAtMillis
    )
  }

  private fun exitRecord(
    reason: Int = ApplicationExitInfo.REASON_CRASH,
    status: Int = 0,
    timestampMillis: Long = 1_700_000_000_500,
    pid: Int = 123,
    description: String? = null,
    sessionId: String? = "crashed-session"
  ): ExitRecord = ExitRecord(
    reason = reason,
    status = status,
    description = description,
    timestampMillis = timestampMillis,
    pid = pid,
    sessionId = sessionId
  )

  private suspend fun storedReport(sessionId: String): CrashReport? =
    sessionManager.getCrashReport(sessionId)?.let { CrashReport.decodeFromJsonString(it) }

  // region JVM crash files — release

  @Test
  fun `release promotes a crash file on its own evidence`() =
    runTest {
      // No corroborating exit record (the AEI ring buffer may have evicted it);
      // in release an uncaught exception that reached the chain killed the process.
      writeCrashFile()

      processor(isDebuggableBuild = false).process(currentSessionId = "current")

      val report = storedReport("crashed-session")
      assertNotNull(report)
      assertEquals("java.lang.IllegalStateException: boom", report?.exceptionReason?.composedMessage)
      assertEquals("1.2.3", report?.appVersion)
    }

  @Test
  fun `deletes the crash file after promoting it`() =
    runTest {
      writeCrashFile()

      processor(isDebuggableBuild = false).process(currentSessionId = null)

      assertEquals(emptyList<PendingJvmCrash>(), crashFileWriter.listPendingCrashes())
    }

  // endregion

  // region JVM crash files — debuggable (red-box false-positive guard)

  @Test
  fun `debug discards an uncorroborated crash file`() =
    runTest {
      // The dev red box / dev launcher can catch an exception without process
      // death — without a matching exit record the file is not a crash.
      writeCrashFile()

      processor(isDebuggableBuild = true).process(currentSessionId = null)

      assertNull(sessionManager.getCrashReport("crashed-session"))
      assertEquals(emptyList<PendingJvmCrash>(), crashFileWriter.listPendingCrashes())
    }

  @Test
  fun `debug promotes a crash file corroborated by session id`() =
    runTest {
      writeCrashFile(sessionId = "crashed-session")
      exitRecords += exitRecord(reason = ApplicationExitInfo.REASON_CRASH, sessionId = "crashed-session")

      processor(isDebuggableBuild = true).process(currentSessionId = null)

      assertNotNull(storedReport("crashed-session"))
    }

  @Test
  fun `debug promotes a crash file corroborated by pid and time proximity`() =
    runTest {
      // Crash before ProcessIdentity ran: no session id in the OS record.
      writeCrashFile(sessionId = "crashed-session", pid = 123, crashedAtMillis = 1_700_000_000_000)
      exitRecords += exitRecord(
        reason = ApplicationExitInfo.REASON_CRASH,
        sessionId = null,
        pid = 123,
        timestampMillis = 1_700_000_002_000
      )

      processor(isDebuggableBuild = true).process(currentSessionId = null)

      assertNotNull(storedReport("crashed-session"))
    }

  @Test
  fun `debug promotes a crash file corroborated by a signaled death`() =
    runTest {
      writeCrashFile()
      exitRecords += exitRecord(reason = ApplicationExitInfo.REASON_SIGNALED, status = 9)

      processor(isDebuggableBuild = true).process(currentSessionId = null)

      assertNotNull(storedReport("crashed-session"))
    }

  @Test
  fun `debug does not corroborate with a non-death exit record`() =
    runTest {
      writeCrashFile()
      exitRecords += exitRecord(reason = ApplicationExitInfo.REASON_USER_REQUESTED)

      processor(isDebuggableBuild = true).process(currentSessionId = null)

      assertNull(sessionManager.getCrashReport("crashed-session"))
    }

  @Test
  fun `debug does not corroborate with a record outside the pid time window`() =
    runTest {
      // Pids get reused — same pid but 6 minutes apart is a different death.
      writeCrashFile(sessionId = null, pid = 123, crashedAtMillis = 1_700_000_000_000)
      exitRecords += exitRecord(
        reason = ApplicationExitInfo.REASON_CRASH,
        sessionId = null,
        pid = 123,
        timestampMillis = 1_700_000_000_000 + 6 * 60 * 1000
      )

      processor(isDebuggableBuild = true).process(currentSessionId = null)

      assertEquals(0, countCrashReports())
    }

  @Test
  fun `debug does not corroborate with a record of a different pid`() =
    runTest {
      writeCrashFile(sessionId = null, pid = 123)
      exitRecords += exitRecord(sessionId = null, pid = 456)

      processor(isDebuggableBuild = true).process(currentSessionId = null)

      assertTrue(processedKeys.isNotEmpty()) // run completed
      assertEquals(0, countCrashReports())
    }

  @Test
  fun `discards a crash file carrying the current session id`() =
    runTest {
      // The handler ran but the process survived (something downstream swallowed
      // the exception) — a file stamped with the live session id is not a crash.
      writeCrashFile(sessionId = "current")

      processor(isDebuggableBuild = false).process(currentSessionId = "current")

      assertEquals(0, countCrashReports())
      assertEquals(emptyList<PendingJvmCrash>(), crashFileWriter.listPendingCrashes())
    }

  // endregion

  // region Dedup: file wins over its own exit record

  @Test
  fun `a record corroborates only one file`() =
    runTest {
      // Two crash-burst files from different pids; the single death record may
      // vouch for one of them only — the other must stand on its own evidence.
      writeCrashFile(sessionId = "first", pid = 123, crashedAtMillis = 1_700_000_000_000)
      writeCrashFile(sessionId = "second", pid = 456, crashedAtMillis = 1_700_000_100_000)
      exitRecords += exitRecord(
        reason = ApplicationExitInfo.REASON_CRASH,
        sessionId = null,
        pid = 123,
        timestampMillis = 1_700_000_001_000
      )

      processor(isDebuggableBuild = true).process(currentSessionId = null)

      // Only the pid-123 file is corroborated; the second is discarded in debug.
      assertNotNull(storedReport("first"))
      assertNull(sessionManager.getCrashReport("second"))
    }

  @Test
  fun `a corroborated crash produces exactly one report with the file's rich content`() =
    runTest {
      writeCrashFile(sessionId = "crashed-session")
      exitRecords += exitRecord(
        reason = ApplicationExitInfo.REASON_CRASH,
        sessionId = "crashed-session",
        description = "bare AEI description"
      )

      processor(isDebuggableBuild = true).process(currentSessionId = null)

      val report = storedReport("crashed-session")
      // The file's exceptionReason survived; the bare record didn't overwrite it.
      assertEquals("java.lang.IllegalStateException", report?.exceptionReason?.exceptionType)
      assertNull(report?.terminationReason)
      assertEquals(1, countCrashReports())
    }

  // endregion

  // region Bare exit records (native crashes, lost files)

  @Test
  fun `stores a native crash from a bare exit record`() =
    runTest {
      exitRecords += exitRecord(
        reason = ApplicationExitInfo.REASON_CRASH_NATIVE,
        status = 11,
        description = "Native crash in libhermes",
        sessionId = "native-session"
      )

      processor().process(currentSessionId = null)

      val report = storedReport("native-session")
      assertEquals(11, report?.signal)
      assertEquals("Native crash in libhermes", report?.terminationReason)
      assertNull(report?.exceptionReason)
      assertEquals("1.2.3", report?.appVersion)
    }

  @Test
  fun `stores a JVM crash from a bare exit record when the file is missing`() =
    runTest {
      exitRecords += exitRecord(reason = ApplicationExitInfo.REASON_CRASH, sessionId = "lost-file-session")

      processor().process(currentSessionId = null)

      val report = storedReport("lost-file-session")
      assertNotNull(report)
      // A Java crash's status is an exit code, not a signal — must stay null.
      assertNull(report?.signal)
    }

  @Test
  fun `ignores exit reasons outside the crash allowlist`() =
    runTest {
      exitRecords += exitRecord(reason = ApplicationExitInfo.REASON_ANR, sessionId = "anr-session")
      exitRecords += exitRecord(reason = ApplicationExitInfo.REASON_LOW_MEMORY, sessionId = "lmk-session")
      exitRecords += exitRecord(reason = ApplicationExitInfo.REASON_SIGNALED, sessionId = "sig-session")
      exitRecords += exitRecord(reason = ApplicationExitInfo.REASON_USER_REQUESTED, sessionId = "user-session")

      processor().process(currentSessionId = null)

      assertEquals(0, countCrashReports())
    }

  @Test
  fun `attributes a summary-less record by timestamp`() =
    runTest {
      // 1_700_000_000_000 = 2023-11-14T22:13:20.000Z
      sessionManager.startSessionWithIdAt("intersecting", "2023-11-14T22:00:00.000Z")
      sessionManager.stopSession("intersecting")
      exitRecords += exitRecord(
        reason = ApplicationExitInfo.REASON_CRASH_NATIVE,
        status = 11,
        sessionId = null,
        timestampMillis = 1_700_000_000_000
      )

      processor().process(currentSessionId = null)

      assertNotNull(storedReport("intersecting"))
    }

  @Test
  fun `never attributes an exit record to the current session`() =
    runTest {
      exitRecords += exitRecord(reason = ApplicationExitInfo.REASON_CRASH, sessionId = "current")

      processor().process(currentSessionId = "current")

      assertNull(sessionManager.getCrashReport("current"))
    }

  @Test
  fun `drops an unattributable record`() =
    runTest {
      exitRecords += exitRecord(reason = ApplicationExitInfo.REASON_CRASH, sessionId = null)

      processor().process(currentSessionId = null)

      assertEquals(0, countCrashReports())
    }

  // endregion

  // region Cursor (at-least-once, no reprocessing)

  @Test
  fun `does not reprocess exit records on later runs`() =
    runTest {
      exitRecords += exitRecord(reason = ApplicationExitInfo.REASON_CRASH, sessionId = "crashed-session")
      processor().process(currentSessionId = null)

      // Overwrite the stored payload; a reprocessed record would clobber it.
      sessionManager.setCrashReport("crashed-session", """{"marker":true}""")
      processor().process(currentSessionId = null)

      assertEquals("""{"marker":true}""", sessionManager.getCrashReport("crashed-session"))
    }

  @Test
  fun `keeps the processed-record set bounded to the current buffer`() =
    runTest {
      processedKeys += "9999999:1:4" // a record long evicted from the AEI buffer
      exitRecords += exitRecord(reason = ApplicationExitInfo.REASON_CRASH, sessionId = "crashed-session")

      processor().process(currentSessionId = null)

      // Only the records currently in the buffer remain tracked.
      assertEquals(setOf(exitRecords.single().key), processedKeys)
    }

  // endregion

  // region Persistence-failure resilience

  @Test
  fun `keeps the crash file for retry when the database write fails`() =
    runTest {
      writeCrashFile(sessionId = "crashed-session")
      database.close()

      processor(isDebuggableBuild = false).process(currentSessionId = null)

      assertEquals(1, crashFileWriter.listPendingCrashes().size)
    }

  @Test
  fun `does not advance the cursor for a record whose write fails`() =
    runTest {
      exitRecords += exitRecord(reason = ApplicationExitInfo.REASON_CRASH, sessionId = "crashed-session")
      database.close()

      processor().process(currentSessionId = null)

      // The key stays out of the processed set, so the next launch retries it.
      assertEquals(emptySet<String>(), processedKeys)
    }

  // endregion

  // region Files without a session id

  @Test
  fun `release attributes an identity-less file by timestamp`() =
    runTest {
      // 1_700_000_000_000 = 2023-11-14T22:13:20.000Z
      sessionManager.startSessionWithIdAt("intersecting", "2023-11-14T22:00:00.000Z")
      sessionManager.stopSession("intersecting")
      writeCrashFile(sessionId = null, crashedAtMillis = 1_700_000_000_000)

      processor(isDebuggableBuild = false).process(currentSessionId = null)

      val report = storedReport("intersecting")
      assertEquals("java.lang.IllegalStateException: boom", report?.exceptionReason?.composedMessage)
    }

  @Test
  fun `drops an unattributable file without storing anything`() =
    runTest {
      writeCrashFile(sessionId = null)

      processor(isDebuggableBuild = false).process(currentSessionId = null)

      assertEquals(0, countCrashReports())
      assertEquals(emptyList<PendingJvmCrash>(), crashFileWriter.listPendingCrashes())
    }

  // endregion

  private suspend fun countCrashReports(): Int = database.crashReportDao().count()
}

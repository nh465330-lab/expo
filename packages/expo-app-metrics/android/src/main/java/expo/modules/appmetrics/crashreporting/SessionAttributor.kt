package expo.modules.appmetrics.crashreporting

import expo.modules.appmetrics.storage.Session

/**
 * Timestamp-based crash-to-session attribution — the fallback when the OS
 * record carries no stamped session id. Port of the iOS
 * `CrashReport.findMatchingSession` algorithm; the two implementations must
 * agree (the test suite mirrors the iOS one).
 *
 * Matches against all sessions: Android has no `type` column yet, every stored
 * session is a main session today.
 *
 * Timestamps are the package's ISO 8601 strings and compare lexicographically.
 * On Android the window is always zero-width (`ApplicationExitInfo` gives an
 * exact instant) — kept as a window so the semantics stay a faithful port.
 */
object SessionAttributor {
  /**
   * 1. Prefer sessions whose `[start, end ?? ∞]` interval intersects the
   *    window — an unfinished one first (a session that never closed is a
   *    strong crash signal), otherwise the latest by start.
   * 2. If nothing intersects and the window is zero-width, fall back to all
   *    sessions with the same preference order.
   * 3. Otherwise `null` — a real window that overlaps nothing is genuinely
   *    unattributable, and guessing would hide that.
   */
  fun findMatchingSession(
    timestampBegin: String,
    timestampEnd: String,
    sessions: List<Session>
  ): Session? {
    val intersecting = sessions.filter { session ->
      if (session.startTimestamp > timestampEnd) {
        return@filter false
      }
      // No end timestamp means the session never finished — it overlaps
      // anything in its future.
      val sessionEnd = session.endTimestamp ?: return@filter true
      sessionEnd >= timestampBegin
    }
    val candidates = when {
      intersecting.isNotEmpty() -> intersecting
      timestampBegin == timestampEnd -> sessions
      else -> return null
    }
    val unfinished = candidates.filter { it.endTimestamp == null }
    return unfinished.maxByOrNull { it.startTimestamp }
      ?: candidates.maxByOrNull { it.startTimestamp }
  }
}

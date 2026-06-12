package expo.modules.appmetrics.crashreporting

import expo.modules.appmetrics.storage.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Port of the iOS `CrashReportTests.findMatchingSession` suite — the two
 * implementations must agree on attribution semantics.
 */
class SessionAttributorTest {
  private fun session(
    id: String,
    startTimestamp: String,
    endTimestamp: String? = null
  ): Session = Session(
    id = id,
    startTimestamp = startTimestamp,
    endTimestamp = endTimestamp,
    isActive = endTimestamp == null
  )

  private fun find(begin: String, end: String, sessions: List<Session>): Session? =
    SessionAttributor.findMatchingSession(begin, end, sessions)

  @Test
  fun `prefers an unfinished session inside the window`() {
    val unfinished = session("unfinished", "2026-06-12T09:30:00.000Z")
    val finished = session("finished", "2026-06-12T09:40:00.000Z", "2026-06-12T09:50:00.000Z")

    val match = find("2026-06-12T09:00:00.000Z", "2026-06-12T10:00:00.000Z", listOf(finished, unfinished))

    assertEquals("unfinished", match?.id)
  }

  @Test
  fun `picks the latest unfinished session when multiple are in the window`() {
    val earlier = session("earlier", "2026-06-12T09:20:00.000Z")
    val later = session("later", "2026-06-12T09:40:00.000Z")

    val match = find("2026-06-12T09:00:00.000Z", "2026-06-12T10:00:00.000Z", listOf(earlier, later))

    assertEquals("later", match?.id)
  }

  @Test
  fun `falls back to the latest finished session in the window`() {
    val earlier = session("earlier", "2026-06-12T09:20:00.000Z", "2026-06-12T09:26:00.000Z")
    val later = session("later", "2026-06-12T09:40:00.000Z", "2026-06-12T09:50:00.000Z")

    val match = find("2026-06-12T09:00:00.000Z", "2026-06-12T10:00:00.000Z", listOf(earlier, later))

    assertEquals("later", match?.id)
  }

  @Test
  fun `matches a session whose interval intersects the window even if its start predates the window`() {
    val spanning = session("spanning", "2026-06-12T08:43:00.000Z")

    val match = find("2026-06-12T09:00:00.000Z", "2026-06-12T10:00:00.000Z", listOf(spanning))

    assertEquals("spanning", match?.id)
  }

  @Test
  fun `falls back to the latest unfinished session when nothing intersects a zero-width window`() {
    // The probe predates every session (an unfinished session would intersect
    // any *later* probe), so the intersecting set is empty and the zero-width
    // fallback considers all sessions, preferring the unfinished one.
    val oldFinished = session("old-finished", "2026-06-12T08:00:00.000Z", "2026-06-12T08:03:00.000Z")
    val laterFinished = session("later-finished", "2026-06-12T09:00:00.000Z", "2026-06-12T09:30:00.000Z")
    val unfinished = session("unfinished", "2026-06-12T08:30:00.000Z")

    val match = find(
      "2026-06-12T07:00:00.000Z",
      "2026-06-12T07:00:00.000Z",
      listOf(oldFinished, laterFinished, unfinished)
    )

    assertEquals("unfinished", match?.id)
  }

  @Test
  fun `falls back to the latest session by start when nothing intersects a zero-width window and none are unfinished`() {
    val earlier = session("earlier", "2026-06-12T08:00:00.000Z", "2026-06-12T08:03:00.000Z")
    val later = session("later", "2026-06-12T09:00:00.000Z", "2026-06-12T09:01:00.000Z")

    val match = find("2026-06-12T12:00:00.000Z", "2026-06-12T12:00:00.000Z", listOf(earlier, later))

    assertEquals("later", match?.id)
  }

  @Test
  fun `returns null for a real window that intersects no session`() {
    // A non-zero-width window that overlaps nothing must NOT be silently
    // attributed to the latest session.
    val unfinishedToday = session("unfinished-today", "2026-06-12T09:59:00.000Z")
    val finishedToday = session("finished-today", "2026-06-12T09:00:00.000Z", "2026-06-12T09:30:00.000Z")

    val match = find("2026-06-11T08:00:00.000Z", "2026-06-11T09:00:00.000Z", listOf(unfinishedToday, finishedToday))

    assertNull(match)
  }

  @Test
  fun `returns null when the input is empty`() {
    assertNull(find("2026-06-12T09:00:00.000Z", "2026-06-12T10:00:00.000Z", emptyList()))
  }

  @Test
  fun `matches an exact crash instant inside a finished session`() {
    // The Android common case: ApplicationExitInfo gives a point in time.
    val crashed = session("crashed", "2026-06-12T09:00:00.000Z", "2026-06-12T09:30:00.000Z")
    val next = session("next", "2026-06-12T09:31:00.000Z", "2026-06-12T09:45:00.000Z")

    val match = find("2026-06-12T09:15:00.000Z", "2026-06-12T09:15:00.000Z", listOf(crashed, next))

    assertEquals("crashed", match?.id)
  }
}

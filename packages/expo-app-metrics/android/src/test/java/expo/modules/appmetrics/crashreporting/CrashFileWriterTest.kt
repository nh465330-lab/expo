package expo.modules.appmetrics.crashreporting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CrashFileWriterTest {
  @get:Rule
  val tmp = TemporaryFolder()

  private fun writer(directory: File = tmp.root): CrashFileWriter =
    CrashFileWriter(directory).also { it.prepare() }

  private fun throwable(message: String? = "boom"): Throwable = IllegalStateException(message)

  // region write

  @Test
  fun `writes one parseable file per crash`() {
    val writer = writer()

    val file = writer.write(
      throwable = throwable(),
      threadName = "main",
      sessionId = "session-1",
      pid = 123,
      crashedAtMillis = 1_700_000_000_000
    )

    assertNotNull(file)
    assertTrue(file!!.exists())
    assertTrue(file.name.startsWith("crash-123-1700000000000"))
    assertFalse(file.name.endsWith(".tmp"))
  }

  @Test
  fun `leaves no temp files behind`() {
    val writer = writer()

    writer.write(throwable(), "main", "session-1", 123, 1_700_000_000_000)

    assertTrue(tmp.root.listFiles()!!.none { it.name.endsWith(".tmp") })
  }

  @Test
  fun `returns null instead of throwing when the directory is not writable`() {
    // Point the writer at a path occupied by a regular file so every write fails.
    val blocked = tmp.newFile("not-a-directory")
    val writer = CrashFileWriter(blocked)
    writer.prepare()

    val file = writer.write(throwable(), "main", "session-1", 123, 1_700_000_000_000)

    assertNull(file)
  }

  @Test
  fun `releases the emergency buffer on write`() {
    val writer = writer()
    assertNotNull(writer.emergencyBuffer)

    writer.write(throwable(), "main", "session-1", 123, 1_700_000_000_000)

    assertNull(writer.emergencyBuffer)
  }

  // endregion

  // region parse round-trip

  @Test
  fun `round-trips the crash metadata`() {
    val writer = writer()
    writer.write(throwable("boom"), "worker-thread", "session-1", 123, 1_700_000_000_000)

    val pending = writer.listPendingCrashes().single()

    assertEquals("session-1", pending.sessionId)
    assertEquals(123, pending.pid)
    assertEquals(1_700_000_000_000, pending.crashedAtMillis)
    assertEquals("java.lang.IllegalStateException", pending.exceptionClass)
    assertEquals("java.lang.IllegalStateException: boom", pending.composedMessage)
    assertEquals("worker-thread", pending.threadName)
  }

  @Test
  fun `round-trips the cause chain in the composed message`() {
    val writer = writer()
    val wrapped = RuntimeException("wrapper", IllegalStateException("root cause"))
    writer.write(wrapped, "main", "session-1", 123, 1_700_000_000_000)

    val pending = writer.listPendingCrashes().single()

    assertEquals(
      "java.lang.RuntimeException: wrapper\nCaused by: java.lang.IllegalStateException: root cause",
      pending.composedMessage
    )
  }

  @Test
  fun `round-trips the stack frames in order`() {
    val writer = writer()
    writer.write(throwable(), "main", "session-1", 123, 1_700_000_000_000)

    val pending = writer.listPendingCrashes().single()

    assertTrue(pending.stackFrames.isNotEmpty())
    // The crash site (this test class) leads the frames.
    assertTrue(pending.stackFrames.first().contains("CrashFileWriterTest"))
  }

  @Test
  fun `round-trips a null session id`() {
    // Crashes before the session identity exists must still be recorded.
    val writer = writer()
    writer.write(throwable(), "main", sessionId = null, pid = 123, crashedAtMillis = 1_700_000_000_000)

    val pending = writer.listPendingCrashes().single()

    assertNull(pending.sessionId)
  }

  @Test
  fun `round-trips a message-less exception`() {
    val writer = writer()
    writer.write(throwable(message = null), "main", "session-1", 123, 1_700_000_000_000)

    val pending = writer.listPendingCrashes().single()

    assertEquals("java.lang.IllegalStateException", pending.composedMessage)
  }

  @Test
  fun `round-trips messages containing newlines and equals signs`() {
    val writer = writer()
    writer.write(
      throwable("first line\nsecond=line"),
      "main",
      "session-1",
      123,
      1_700_000_000_000
    )

    val pending = writer.listPendingCrashes().single()

    assertEquals(
      "java.lang.IllegalStateException: first line\nsecond=line",
      pending.composedMessage
    )
  }

  @Test
  fun `a multi-line message cannot inject or truncate stack frames`() {
    // Frame lines are written escaped below the separator; the message never
    // appears there, so "\n\tat …" / "\nCaused by:" payloads can't pollute
    // the parsed frames.
    val writer = writer()
    writer.write(
      throwable("first\n\tat com.fake.Injected.method(Fake.java:1)\nCaused by: com.fake.FakeCause"),
      "main",
      "session-1",
      123,
      1_700_000_000_000
    )

    val pending = writer.listPendingCrashes().single()

    assertTrue(pending.stackFrames.first().contains("CrashFileWriterTest"))
    assertTrue(pending.stackFrames.none { it.contains("com.fake.Injected") })
  }

  @Test
  fun `a message line of exactly the header separator does not break parsing`() {
    val writer = writer()
    writer.write(throwable("before\n---\nafter"), "main", "session-1", 123, 1_700_000_000_000)

    val pending = writer.listPendingCrashes().single()

    assertEquals(
      "java.lang.IllegalStateException: before\n---\nafter",
      pending.composedMessage
    )
    assertTrue(pending.stackFrames.first().contains("CrashFileWriterTest"))
  }

  @Test
  fun `the file path and fromThrowable produce the same report content`() {
    // Two builders, one contract: a report normalized from the pending file
    // must match what `fromThrowable` would have produced at crash time.
    val writer = writer()
    val throwable = RuntimeException("boom", IllegalStateException("root"))
    writer.write(throwable, "main", "session-1", 123, 1_700_000_000_000)
    val pending = writer.listPendingCrashes().single()

    val fromFile = pending.toCrashReport(ingestedAt = "2026-06-12T10:05:00.000Z", appVersion = "1.0.0")
    val direct = CrashReport.fromThrowable(
      throwable = throwable,
      crashTimestamp = fromFile.timestampBegin,
      ingestedAt = "2026-06-12T10:05:00.000Z",
      appVersion = "1.0.0"
    )

    assertEquals(direct.exceptionReason, fromFile.exceptionReason)
    assertEquals(direct.callStackTree, fromFile.callStackTree)
  }

  @Test
  fun `keeps separate files for separate crashes`() {
    val writer = writer()
    writer.write(throwable(), "main", "session-1", pid = 123, crashedAtMillis = 1_700_000_000_000)
    writer.write(throwable(), "main", "session-2", pid = 456, crashedAtMillis = 1_700_000_000_500)

    val pending = writer.listPendingCrashes()

    assertEquals(2, pending.size)
    assertEquals(setOf("session-1", "session-2"), pending.map { it.sessionId }.toSet())
  }

  @Test
  fun `a second write with the same pid and timestamp overwrites cleanly`() {
    // Same filename by construction (double-installed handlers in one tick) —
    // last write wins; the file must stay parseable, never corrupt.
    val writer = writer()
    writer.write(throwable("first"), "main", "session-1", pid = 123, crashedAtMillis = 1_700_000_000_000)
    writer.write(throwable("second"), "main", "session-1", pid = 123, crashedAtMillis = 1_700_000_000_000)

    val pending = writer.listPendingCrashes()

    assertEquals(1, pending.size)
    assertEquals("java.lang.IllegalStateException: second", pending.single().composedMessage)
  }

  // endregion

  // region listPendingCrashes hygiene

  @Test
  fun `skips corrupt files without throwing`() {
    val writer = writer()
    writer.write(throwable(), "main", "session-1", 123, 1_700_000_000_000)
    tmp.newFile("crash-999-1700000000001.txt").writeText("complete garbage")

    val pending = writer.listPendingCrashes()

    assertEquals(1, pending.size)
    assertEquals("session-1", pending.single().sessionId)
  }

  @Test
  fun `ignores temp files and unrelated files`() {
    val writer = writer()
    tmp.newFile("crash-1-2.txt.tmp").writeText("partial write")
    tmp.newFile("unrelated.json").writeText("{}")

    assertEquals(emptyList<PendingJvmCrash>(), writer.listPendingCrashes())
  }

  @Test
  fun `returns an empty list when the directory does not exist`() {
    val writer = CrashFileWriter(File(tmp.root, "missing"))

    assertEquals(emptyList<PendingJvmCrash>(), writer.listPendingCrashes())
  }

  @Test
  fun `delete removes the pending crash file`() {
    val writer = writer()
    writer.write(throwable(), "main", "session-1", 123, 1_700_000_000_000)
    val pending = writer.listPendingCrashes().single()

    writer.delete(pending)

    assertEquals(emptyList<PendingJvmCrash>(), writer.listPendingCrashes())
  }

  // endregion
}

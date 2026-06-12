package expo.modules.appmetrics.crashreporting

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowActivityManager

@RunWith(RobolectricTestRunner::class)
class AndroidExitInfoProviderTest {
  private val context: Context
    get() = ApplicationProvider.getApplicationContext()

  private fun seedExitInfo(
    reason: Int = ApplicationExitInfo.REASON_CRASH,
    status: Int = 0,
    timestamp: Long = 1_700_000_000_000,
    pid: Int = 123,
    processName: String = Application.getProcessName(),
    description: String? = null,
    processStateSummary: ByteArray? = null
  ) {
    val activityManager = context.getSystemService(ActivityManager::class.java)
    val builder = ShadowActivityManager.ApplicationExitInfoBuilder.newBuilder()
      .setReason(reason)
      .setStatus(status)
      .setTimestamp(timestamp)
      .setPid(pid)
      .setProcessName(processName)
    description?.let { builder.setDescription(it) }
    processStateSummary?.let { builder.setProcessStateSummary(it) }
    shadowOf(activityManager).addApplicationExitInfo(builder.build())
  }

  @Test
  @Config(manifest = Config.NONE, sdk = [30])
  fun `maps exit records into ExitRecord`() {
    val sessionId = "0b9e6a43-9d6a-4a83-bb35-94e9aa4f4e30"
    seedExitInfo(
      reason = ApplicationExitInfo.REASON_CRASH_NATIVE,
      status = 11,
      timestamp = 1_700_000_000_000,
      pid = 123,
      description = "Native crash",
      processStateSummary = sessionId.toByteArray(Charsets.UTF_8)
    )

    val records = AndroidExitInfoProvider(context).getExitRecords()

    val record = records.single()
    assertEquals(ApplicationExitInfo.REASON_CRASH_NATIVE, record.reason)
    assertEquals(11, record.status)
    assertEquals(1_700_000_000_000, record.timestampMillis)
    assertEquals(123, record.pid)
    assertEquals("Native crash", record.description)
    assertEquals(sessionId, record.sessionId)
  }

  @Test
  @Config(manifest = Config.NONE, sdk = [30])
  fun `yields a null session id when no process state summary was stamped`() {
    seedExitInfo(processStateSummary = null)

    val record = AndroidExitInfoProvider(context).getExitRecords().single()

    assertNull(record.sessionId)
  }

  @Test
  @Config(manifest = Config.NONE, sdk = [30])
  fun `yields a null session id for an empty process state summary`() {
    seedExitInfo(processStateSummary = ByteArray(0))

    val record = AndroidExitInfoProvider(context).getExitRecords().single()

    assertNull(record.sessionId)
  }

  @Test
  @Config(manifest = Config.NONE, sdk = [30])
  fun `rejects a process state summary stamped by another SDK`() {
    // The summary slot is shared and last-writer-wins; only a UUID is ours.
    // Foreign bytes must fall through to pid/timestamp attribution instead of
    // keying a crash report by garbage.
    seedExitInfo(processStateSummary = "sentry:release@1.0".toByteArray(Charsets.UTF_8))

    val record = AndroidExitInfoProvider(context).getExitRecords().single()

    assertNull(record.sessionId)
  }

  @Test
  @Config(manifest = Config.NONE, sdk = [30])
  fun `filters out records from other processes of the package`() {
    // ApplicationExitInfo reports every process of the package; a future
    // `:background` worker's death must not be attributed to the main session.
    seedExitInfo(processName = Application.getProcessName(), pid = 1)
    seedExitInfo(processName = "com.example.app:background", pid = 2)

    val records = AndroidExitInfoProvider(context).getExitRecords()

    assertEquals(listOf(1), records.map { it.pid })
  }

  @Test
  @Config(manifest = Config.NONE, sdk = [28])
  fun `returns no records below API 30`() {
    assertEquals(emptyList<ExitRecord>(), AndroidExitInfoProvider(context).getExitRecords())
  }
}

package expo.modules.appmetrics.crashreporting

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class ProcessIdentityTest {
  @After
  fun reset() {
    ProcessIdentity.resetForTesting()
  }

  private val context: Context
    get() = ApplicationProvider.getApplicationContext()

  @Test
  @Config(manifest = Config.NONE, sdk = [28])
  fun `generates a stable session id per process`() {
    val first = ProcessIdentity.initialize(context)
    val second = ProcessIdentity.initialize(context)

    assertNotNull(first)
    assertEquals(first, second)
    assertEquals(first, ProcessIdentity.sessionId)
  }

  @Test
  @Config(manifest = Config.NONE, sdk = [28])
  fun `sessionId is null before initialize`() {
    assertNull(ProcessIdentity.sessionId)
  }

  @Test
  @Config(manifest = Config.NONE, sdk = [28])
  fun `initialize works below API 30 where no process state summary exists`() {
    // Just must not throw — `setProcessStateSummary` is API 30+.
    assertNotNull(ProcessIdentity.initialize(context))
  }

  @Test
  @Config(manifest = Config.NONE, sdk = [30])
  fun `initialize takes the setProcessStateSummary path on API 30+ without throwing`() {
    // Robolectric has no shadow accessor to read the summary back; this pins
    // down that the API-30 code path executes cleanly. The end-to-end matching
    // against `ApplicationExitInfo.getProcessStateSummary()` is covered by the
    // CrashReportProcessor tests, which seed exit records with a summary.
    assertNotNull(ProcessIdentity.initialize(context))
  }
}

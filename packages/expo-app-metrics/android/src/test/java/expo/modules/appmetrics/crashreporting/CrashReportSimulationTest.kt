package expo.modules.appmetrics.crashreporting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashReportSimulationTest {
  @Test
  fun `produces an Android-shaped report`() {
    val report = CrashReportSimulation.simulate(appVersion = "1.2.3")

    // JVM details live in exceptionReason; the numeric Mach/signal fields
    // (which consumers render via EXC_*/SIG* lookups) must stay null.
    val reason = requireNotNull(report.exceptionReason)
    assertTrue(reason.exceptionType.startsWith("java."))
    assertNull(report.exceptionType)
    assertNull(report.signal)
    assertEquals("1.2.3", report.appVersion)
    assertEquals(report.timestampBegin, report.timestampEnd)
    val frames = report.callStackTree?.callStacks?.firstOrNull()?.callStackRootFrames
    assertNotNull(frames)
    assertTrue(frames!!.all { it.symbol != null })
  }

  @Test
  fun `falls back to an unknown app version`() {
    assertEquals("unknown", CrashReportSimulation.simulate(appVersion = null).appVersion)
  }

  @Test
  fun `round-trips through the payload encoding`() {
    val report = CrashReportSimulation.simulate(appVersion = "1.0.0")

    assertEquals(report, CrashReport.decodeFromJsonString(report.encodeToJsonString()))
  }
}

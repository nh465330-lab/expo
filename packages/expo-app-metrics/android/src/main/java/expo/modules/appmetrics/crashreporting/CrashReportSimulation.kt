package expo.modules.appmetrics.crashreporting

import expo.modules.appmetrics.utils.TimeUtils
import kotlin.random.Random

/**
 * Creates a randomized, Android-shaped crash report for testing the storage and
 * display path — `exceptionReason` carries a JVM class name and the call stack
 * uses symbolic Java frames, so simulated data looks like real Android reports
 * (the iOS counterpart simulates Mach exception numbers instead).
 */
object CrashReportSimulation {
  private val exceptions = listOf(
    "java.lang.NullPointerException" to
      "Attempt to invoke virtual method 'int java.lang.String.length()' on a null object reference",
    "java.lang.IllegalStateException" to
      "Fragment MainFragment not attached to an activity",
    "java.lang.IndexOutOfBoundsException" to
      "Index: 5, Size: 2",
    "java.lang.OutOfMemoryError" to
      "Failed to allocate a 16777232 byte allocation with 4194304 free bytes",
    "java.lang.RuntimeException" to
      "Unable to start activity ComponentInfo{com.example/.MainActivity}"
  )

  private val frames = listOf(
    "com.example.app.MainActivity.onCreate(MainActivity.kt:42)",
    "android.app.Activity.performCreate(Activity.java:8051)",
    "android.app.ActivityThread.handleLaunchActivity(ActivityThread.java:3409)",
    "android.os.Handler.dispatchMessage(Handler.java:106)",
    "android.os.Looper.loopOnce(Looper.java:201)",
    "android.os.Looper.loop(Looper.java:288)",
    "android.app.ActivityThread.main(ActivityThread.java:7839)",
    "java.lang.reflect.Method.invoke(Native Method)",
    "com.android.internal.os.ZygoteInit.main(ZygoteInit.java:971)"
  )

  fun simulate(appVersion: String?): CrashReport {
    val (exceptionClass, message) = exceptions.random()
    val hoursAgoMillis = Random.nextLong(1, 24) * 60 * 60 * 1000
    val crashedAtMillis = System.currentTimeMillis() - hoursAgoMillis
    val crashTimestamp = TimeUtils.millisToTimestamp(crashedAtMillis)
    return CrashReport(
      exceptionReason = CrashReport.exceptionReason(
        exceptionClass = exceptionClass,
        composedMessage = "$exceptionClass: $message"
      ),
      callStackTree = CallStackTreeBuilder.fromSymbols(
        frames.drop(Random.nextInt(0, 3))
      ),
      appVersion = appVersion ?: "unknown",
      timestampBegin = crashTimestamp,
      timestampEnd = crashTimestamp,
      ingestedAt = TimeUtils.getCurrentTimestampInISOFormat()
    )
  }
}

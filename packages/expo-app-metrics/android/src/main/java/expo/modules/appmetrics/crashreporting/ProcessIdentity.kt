package expo.modules.appmetrics.crashreporting

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import java.util.UUID

/**
 * The main session's identity, established at process start — *before* the
 * module system spins up. `AppMetricsModule.OnCreate` runs lazily (only when JS
 * first touches the module), so anything crash-related that waits for it misses
 * startup crashes entirely. Instead the application lifecycle listener calls
 * [initialize] in `Application.onCreate`, and the module later adopts this id
 * for its main session row.
 *
 * On API 30+ the id is also stamped into the OS via
 * [ActivityManager.setProcessStateSummary]: when the process dies — for any
 * reason — the system attaches these bytes to the matching
 * `ApplicationExitInfo` record, giving exact crash-to-session attribution on
 * the next launch (the same mechanism Sentry and Bugsnag use).
 */
object ProcessIdentity {
  /** The per-process session id, or `null` before [initialize] ran. */
  @Volatile
  var sessionId: String? = null
    private set

  /** Generates (once per process) and returns the session id. Thread-safe and idempotent. */
  fun initialize(context: Context): String {
    sessionId?.let { return it }
    synchronized(this) {
      sessionId?.let { return it }
      val id = UUID.randomUUID().toString()
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        // Best-effort: the summary is attribution metadata, never a reason to fail startup.
        runCatching {
          context.getSystemService(ActivityManager::class.java)
            ?.setProcessStateSummary(id.toByteArray(Charsets.UTF_8))
        }
      }
      sessionId = id
      return id
    }
  }

  internal fun resetForTesting() {
    sessionId = null
  }
}

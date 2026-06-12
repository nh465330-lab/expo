package expo.modules.appmetrics.crashreporting

import expo.modules.appmetrics.utils.TimeUtils
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.io.PrintWriter

/**
 * Crash-time persistence for JVM crashes. The write path runs inside a dying
 * process — possibly under `OutOfMemoryError` — so it stays minimal: a few
 * escaped `key=value` header lines plus one escaped stack frame per line,
 * written to a temp file and atomically renamed. No JSON, no database, no
 * coroutines; the structured [CrashReport] is assembled from the parsed file
 * on the next launch, where allocation is safe.
 *
 * One file per pid+timestamp, so a crash burst (crash → relaunch → crash)
 * can't overwrite a file the processor hasn't read yet.
 */
class CrashFileWriter(private val directory: File) {
  /**
   * Headroom released first thing on the write path so that an
   * `OutOfMemoryError` crash can still allocate the writer machinery.
   */
  internal var emergencyBuffer: ByteArray? = null

  /** Creates the directory and reserves the emergency buffer. Called at install, never at crash time. */
  fun prepare() {
    directory.mkdirs()
    emergencyBuffer = ByteArray(EMERGENCY_BUFFER_SIZE)
  }

  /**
   * Writes one pending-crash file. Returns the file, or `null` on any failure —
   * this runs on the crash path and must never throw into the handler chain.
   */
  fun write(
    throwable: Throwable,
    threadName: String,
    sessionId: String?,
    pid: Int,
    crashedAtMillis: Long
  ): File? {
    emergencyBuffer = null
    return try {
      if (!directory.isDirectory && !directory.mkdirs()) {
        return null
      }
      val file = File(directory, "crash-$pid-$crashedAtMillis.txt")
      val tempFile = File(directory, file.name + TEMP_SUFFIX)
      PrintWriter(BufferedWriter(OutputStreamWriter(FileOutputStream(tempFile), Charsets.UTF_8))).use { writer ->
        writer.append("sessionId=").append(escape(sessionId ?: "")).append('\n')
        writer.append("pid=").append(pid.toString()).append('\n')
        writer.append("crashedAt=").append(crashedAtMillis.toString()).append('\n')
        writer.append("thread=").append(escape(threadName)).append('\n')
        writer.append("exceptionClass=").append(escape(throwable.javaClass.name)).append('\n')
        writer.append("composedMessage=").append(escape(CrashReport.composeMessage(throwable))).append('\n')
        writer.append(HEADER_SEPARATOR).append('\n')
        // One escaped frame per line, from the same source `fromThrowable` uses.
        // Never `printStackTrace` here: it re-emits the message unescaped, and a
        // message containing "\n\tat …" lines would inject fake frames into the
        // parse (or truncate the real ones at an embedded "Caused by:").
        for (element in throwable.stackTrace) {
          writer.append(escape(element.toString())).append('\n')
        }
      }
      if (tempFile.renameTo(file)) {
        file
      } else {
        tempFile.delete()
        null
      }
    } catch (_: Throwable) {
      null
    }
  }

  /**
   * Parses every pending-crash file in the directory. Corrupt or partially
   * written files are skipped (a `.tmp` that never got renamed is invisible by
   * construction).
   */
  fun listPendingCrashes(): List<PendingJvmCrash> {
    val files = directory.listFiles { file -> FILE_NAME_PATTERN.matches(file.name) } ?: return emptyList()
    return files.mapNotNull { parse(it) }
  }

  fun delete(pendingCrash: PendingJvmCrash) {
    pendingCrash.file.delete()
  }

  private fun parse(file: File): PendingJvmCrash? =
    runCatching {
      val lines = file.readLines()
      val separatorIndex = lines.indexOf(HEADER_SEPARATOR)
      if (separatorIndex < 0) {
        return null
      }
      val header = lines.take(separatorIndex).mapNotNull { line ->
        val separator = line.indexOf('=')
        if (separator < 0) null else line.substring(0, separator) to line.substring(separator + 1)
      }.toMap()
      val pid = header["pid"]?.toIntOrNull() ?: return null
      val crashedAtMillis = header["crashedAt"]?.toLongOrNull() ?: return null
      val exceptionClass = header["exceptionClass"]?.takeIf { it.isNotEmpty() } ?: return null
      // Below the separator: one escaped frame per line, primary exception only
      // (the cause chain lives in `composedMessage`).
      val stackFrames = lines.drop(separatorIndex + 1)
        .filter { it.isNotEmpty() }
        .map(::unescape)
      PendingJvmCrash(
        sessionId = header["sessionId"]?.takeIf { it.isNotEmpty() }?.let(::unescape),
        pid = pid,
        crashedAtMillis = crashedAtMillis,
        exceptionClass = unescape(exceptionClass),
        composedMessage = header["composedMessage"]?.let(::unescape) ?: exceptionClass,
        threadName = header["thread"]?.takeIf { it.isNotEmpty() }?.let(::unescape),
        stackFrames = stackFrames,
        file = file
      )
    }.getOrNull()

  // Header values are single-line by construction; messages can contain
  // newlines (cause chains) and `=`, so values are escaped, keys are not.
  private fun escape(value: String): String =
    value.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r")

  private fun unescape(value: String): String {
    val result = StringBuilder(value.length)
    var index = 0
    while (index < value.length) {
      val char = value[index]
      if (char == '\\' && index + 1 < value.length) {
        val next = value[index + 1]
        val unescaped = when (next) {
          'n' -> '\n'
          'r' -> '\r'
          '\\' -> '\\'
          else -> null
        }
        if (unescaped != null) {
          result.append(unescaped)
          index += 2
          continue
        }
      }
      result.append(char)
      index++
    }
    return result.toString()
  }

  companion object {
    private const val EMERGENCY_BUFFER_SIZE = 16 * 1024
    private const val TEMP_SUFFIX = ".tmp"
    private const val HEADER_SEPARATOR = "---"
    private val FILE_NAME_PATTERN = Regex("""crash-\d+-\d+\.txt""")

    /**
     * The canonical pending-crash location — `noBackupFilesDir` so stale crash
     * files never ride along in device-to-device restores.
     */
    fun forContext(context: android.content.Context): CrashFileWriter =
      CrashFileWriter(java.io.File(context.noBackupFilesDir, "expo-app-metrics/crashes"))
  }
}

/**
 * A crash captured by [JvmCrashHandler] in a previous process, parsed back from
 * its pending file on the next launch.
 */
data class PendingJvmCrash(
  /** Session that crashed, or `null` when the crash predated the session identity. */
  val sessionId: String?,
  val pid: Int,
  val crashedAtMillis: Long,
  /** Fully-qualified throwable class name. */
  val exceptionClass: String,
  /** `Throwable.toString()` plus the `Caused by:` chain. */
  val composedMessage: String,
  val threadName: String?,
  /** Symbolic frames of the primary exception, crash site first. */
  val stackFrames: List<String>,
  val file: File
) {
  /**
   * Normalizes the parsed file into the cross-platform [CrashReport] shape.
   * Timestamps use the package's millisecond ISO format so they compare
   * lexicographically against session rows. (iOS emits whole-second ISO in the
   * same fields — both are valid ISO 8601 and consumers must parse, not
   * string-compare, across platforms.)
   */
  fun toCrashReport(ingestedAt: String, appVersion: String): CrashReport {
    val crashTimestamp = TimeUtils.millisToTimestamp(crashedAtMillis)
    return CrashReport(
      exceptionReason = CrashReport.exceptionReason(exceptionClass, composedMessage),
      callStackTree = CallStackTreeBuilder.fromSymbols(stackFrames),
      appVersion = appVersion,
      timestampBegin = crashTimestamp,
      timestampEnd = crashTimestamp,
      ingestedAt = ingestedAt
    )
  }
}

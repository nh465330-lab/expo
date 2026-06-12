package expo.modules.appmetrics.crashreporting

import android.os.Looper
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class CrashTriggersTest {
  // expo-modules-core converts exceptions thrown inside a module `Function`
  // body into JS errors — these triggers only crash the app because the throw
  // happens in a posted Runnable, off the bridge call stack. Each test runs
  // the main looper to execute that Runnable and asserts the escape.

  private fun assertPostsThrow(kind: CrashKind, expected: Class<out Throwable>) {
    CrashTriggers.trigger(kind)
    assertThrows(expected) {
      shadowOf(Looper.getMainLooper()).idle()
    }
  }

  @Test
  fun `fatalError throws a RuntimeException off the bridge stack`() {
    assertPostsThrow(CrashKind.FATAL_ERROR, RuntimeException::class.java)
  }

  @Test
  fun `divideByZero throws an ArithmeticException`() {
    assertPostsThrow(CrashKind.DIVIDE_BY_ZERO, ArithmeticException::class.java)
  }

  @Test
  fun `forceUnwrapNil throws a NullPointerException`() {
    assertPostsThrow(CrashKind.FORCE_UNWRAP_NIL, NullPointerException::class.java)
  }

  @Test
  fun `arrayOutOfBounds throws an IndexOutOfBoundsException`() {
    assertPostsThrow(CrashKind.ARRAY_OUT_OF_BOUNDS, IndexOutOfBoundsException::class.java)
  }

  @Test
  fun `objcException throws an IllegalStateException`() {
    assertPostsThrow(CrashKind.OBJC_EXCEPTION, IllegalStateException::class.java)
  }

  @Test
  fun `stackOverflow overflows`() {
    assertPostsThrow(CrashKind.STACK_OVERFLOW, StackOverflowError::class.java)
  }

  // Note: every test above also proves `trigger` returns normally on the
  // calling thread — the throw only escapes when the looper runs the posted
  // Runnable. `badAccess` (Os.kill) is intentionally untested: it would
  // signal the test process for real.
}

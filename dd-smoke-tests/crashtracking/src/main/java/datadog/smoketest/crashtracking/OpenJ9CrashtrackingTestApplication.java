package datadog.smoketest.crashtracking;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

/**
 * Test application for OpenJ9 crash tracking smoke tests.
 *
 * <p>Waits for the agent to write the crash-uploader script, then crashes the JVM via a null
 * pointer write using {@code sun.misc.Unsafe.putAddress(0L, 0L)} (accessed via reflection so the
 * class compiles against any Java version). This triggers a GPF (general protection fault) on
 * OpenJ9, which the {@code -Xdump:tool:events=gpf+abort,...} handler detects.
 *
 * <p>Note: {@code sun.misc.Unsafe.getLong(0L)} is converted to a Java-level {@link
 * NullPointerException} on Semeru/OpenJ9 25, so it does not exercise crash tracking. {@code
 * sun.misc.Unsafe.putAddress(0L, 0L)} goes directly to {@code unsafePut64} in the JVM native
 * library and produces a native SIGSEGV at address 0.
 *
 * <p>System properties consumed:
 *
 * <ul>
 *   <li>{@code dd.test.crash_script} — path of the crash-uploader script; the application waits for
 *       the agent to write it before crashing, ensuring the agent is fully initialized
 * </ul>
 */
public class OpenJ9CrashtrackingTestApplication {
  public static void main(String[] args) throws Exception {
    // Wait for the agent to write the crash-uploader script (proves initialization is done)
    String scriptPath = System.getProperty("dd.test.crash_script");
    if (scriptPath != null) {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
      while (!Files.exists(Paths.get(scriptPath)) && System.nanoTime() < deadline) {
        Thread.sleep(200);
      }
      if (!Files.exists(Paths.get(scriptPath))) {
        System.err.println("Timeout: crash script not created at " + scriptPath);
        System.exit(-1);
      }
    }

    System.out.println("===> Crash script ready, crashing JVM via Unsafe.putAddress(0L, 0L)...");
    System.out.flush();

    // Write to address 0 via sun.misc.Unsafe to trigger a SIGSEGV (GPF event).
    // Unsafe.getLong(0L) was not enough on OpenJ9 here; it threw a NullPointerException instead.
    Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
    Field f = unsafeClass.getDeclaredField("theUnsafe");
    f.setAccessible(true);
    Object theUnsafe = f.get(null);
    Method putAddress = unsafeClass.getDeclaredMethod("putAddress", long.class, long.class);
    putAddress.invoke(theUnsafe, 0L, 0L);
  }
}

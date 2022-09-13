package datadog.trace.test.util;

import static java.util.concurrent.TimeUnit.MINUTES;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.lang.ref.WeakReference;
import java.util.concurrent.TimeUnit;

@SuppressFBWarnings("DM_GC")
public abstract class GCUtils {

  public static void awaitGC() throws InterruptedException {
    Object obj = new Object();
    final WeakReference<Object> ref = new WeakReference<>(obj);
    obj = null;
    awaitGC(ref);
  }

  public static void awaitGC(final WeakReference<?> ref) throws InterruptedException {
    awaitGC(ref, 1, MINUTES);
  }

  public static void awaitGC(final WeakReference<?> ref, final long duration, final TimeUnit unit)
      throws InterruptedException {
    final long waitNanos = unit.toNanos(duration);
    final long start = System.nanoTime();
    int fillerBytes = 1024 * 1024 * 10;
    byte[] filler;
    while (System.nanoTime() - start < waitNanos) {
      if (ref.get() == null) {
        return;
      }
      if (Thread.interrupted()) {
        throw new InterruptedException();
      }
      System.gc();
      System.runFinalization();
      if (ref.get() == null) {
        return;
      }

      // Allocate large memory chunks in an attempt to trigger GC on OpenJ9 gencon policy (default).
      filler = new byte[fillerBytes];
      if (fillerBytes <= 1024 * 1024 * 200) {
        fillerBytes *= 2;
      }
      if (ref.get() == null) {
        return;
      }
      try {
        Thread.sleep(50);
      } catch (final InterruptedException e) {
        throw new RuntimeException("Interrupted while waiting for " + ref.get() + " to be GCed");
      }
    }
    throw new RuntimeException("Timed out waiting for " + ref.get() + " to be GCed");
  }
}

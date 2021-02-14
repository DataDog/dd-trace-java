package datadog.trace.test.util;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.lang.ref.WeakReference;

@SuppressFBWarnings("DM_GC")
public abstract class GCUtils {

  public static void awaitGC() throws InterruptedException {
    Object obj = new Object();
    final WeakReference<Object> ref = new WeakReference<>(obj);
    obj = null;
    awaitGC(ref);
  }

  public static void awaitGC(final WeakReference<?> ref) throws InterruptedException {
    while (ref.get() != null) {
      if (Thread.interrupted()) {
        throw new InterruptedException();
      }
      System.gc();
      System.runFinalization();
    }
  }
}

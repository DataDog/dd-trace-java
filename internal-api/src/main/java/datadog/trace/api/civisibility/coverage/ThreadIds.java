package datadog.trace.api.civisibility.coverage;

import datadog.environment.JavaVirtualMachine;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.lang.reflect.Field;

/**
 * Reads the id of a thread without calling a JDK method that has a body, which a test framework can
 * instrument: {@link #of} reads the thread's id field through a native method of {@code Unsafe}. On
 * JDK 9 and later it uses the internal {@code Unsafe}, since the {@code sun.misc} one has a body
 * there and warns at first use on JDK 24 and later. The internal one is only accessible once {@code
 * java.base} exports its package to this class, so this class must not initialize before that.
 */
@SuppressFBWarnings(
    value = "UNS_UNSAFE_CALL",
    justification = "A native read of the thread id field that no test framework can instrument")
final class ThreadIds {

  /** Whether {@link #of} reads thread ids; when not, the line probes are not used. */
  static final boolean AVAILABLE;

  private static final boolean INTERNAL;
  private static final jdk.internal.misc.Unsafe INTERNAL_UNSAFE;
  private static final sun.misc.Unsafe SUN_UNSAFE;
  private static final long TID_OFFSET;

  static {
    boolean internal = JavaVirtualMachine.isJavaVersionAtLeast(9);
    jdk.internal.misc.Unsafe internalUnsafe = null;
    sun.misc.Unsafe sunUnsafe = null;
    long offset = -1;
    boolean available;
    try {
      Field tid = Thread.class.getDeclaredField("tid");
      Thread current = Thread.currentThread();
      long read;
      if (internal) {
        internalUnsafe = jdk.internal.misc.Unsafe.getUnsafe();
        offset = internalUnsafe.objectFieldOffset(tid);
        read = internalUnsafe.getLong(current, offset);
      } else {
        Field theUnsafe = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        sunUnsafe = (sun.misc.Unsafe) theUnsafe.get(null);
        offset = sunUnsafe.objectFieldOffset(tid);
        read = sunUnsafe.getLong(current, offset);
      }
      available = read == current.getId();
    } catch (Throwable e) {
      available = false;
    }
    INTERNAL = internal;
    INTERNAL_UNSAFE = internalUnsafe;
    SUN_UNSAFE = sunUnsafe;
    TID_OFFSET = offset;
    AVAILABLE = available;
  }

  private ThreadIds() {}

  /** The id of a thread, as {@link Thread#getId} returns it. Call it only if {@link #AVAILABLE}. */
  static long of(Thread thread) {
    return INTERNAL
        ? INTERNAL_UNSAFE.getLong(thread, TID_OFFSET)
        : SUN_UNSAFE.getLong(thread, TID_OFFSET);
  }
}

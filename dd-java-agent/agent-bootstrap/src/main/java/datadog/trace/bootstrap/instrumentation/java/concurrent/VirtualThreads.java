package datadog.trace.bootstrap.instrumentation.java.concurrent;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Utility for detecting virtual threads (JDK 21+) in a way that is safe on JDK 8+.
 *
 * <p>{@link Thread#isVirtual()} was introduced in JDK 21. We access it through a {@link
 * MethodHandle} resolved once at class-load time so that on older JDKs the resolution simply fails,
 * {@code IS_VIRTUAL} is set to {@code null}, and {@link #isCurrent()} returns {@code false} without
 * any bytecode incompatibility.
 */
public final class VirtualThreads {
  private static final MethodHandle IS_VIRTUAL;

  static {
    MethodHandle mh = null;
    try {
      mh =
          MethodHandles.lookup()
              .findVirtual(Thread.class, "isVirtual", MethodType.methodType(boolean.class));
    } catch (NoSuchMethodException | IllegalAccessException ignored) {
      // JDK < 21: Thread.isVirtual() does not exist; isCurrent() will always return false.
    }
    IS_VIRTUAL = mh;
  }

  private VirtualThreads() {}

  /**
   * Returns {@code true} if the current thread is a virtual thread (JDK 21+), {@code false}
   * otherwise. Never throws.
   */
  public static boolean isCurrent() {
    if (IS_VIRTUAL == null) {
      return false;
    }
    try {
      return (boolean) IS_VIRTUAL.invokeExact(Thread.currentThread());
    } catch (Throwable ignored) {
      return false;
    }
  }
}

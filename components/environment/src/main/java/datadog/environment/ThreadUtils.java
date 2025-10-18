package datadog.environment;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Helper class for working with Threads
 *
 * <p>Uses feature detection and provides static helpers to work with different versions of Java
 *
 * <p>This class is designed to use MethodHandles that constant propagate to minimize the overhead
 */
public final class ThreadUtils {
  static final MethodHandle H_IS_VIRTUAL = lookupIsVirtual();
  static final MethodHandle H_ID = lookupId();

  private ThreadUtils() {}

  /** Provides the best id available for the Thread Uses threadId on 19+; getId on older JVMs */
  public static final long threadId(Thread thread) {
    try {
      return (long) H_ID.invoke(thread);
    } catch (Throwable t) {
      return 0L;
    }
  }

  /** Indicates whether virtual threads are supported on this JVM */
  public static final boolean supportsVirtualThreads() {
    return (H_IS_VIRTUAL != null);
  }

  /** Indicates if the current thread is a virtual thread */
  public static final boolean isCurrentThreadVirtual() {
    // H_IS_VIRTUAL will constant propagate -- then dead code eliminate -- and inline as needed
    try {
      return (H_IS_VIRTUAL != null) && (boolean) H_IS_VIRTUAL.invoke(Thread.currentThread());
    } catch (Throwable t) {
      return false;
    }
  }

  /** Indicates if the provided thread is a virtual thread */
  public static final boolean isVirtual(Thread thread) {
    // H_IS_VIRTUAL will constant propagate -- then dead code eliminate -- and inline as needed
    try {
      return (H_IS_VIRTUAL != null) && (boolean) H_IS_VIRTUAL.invoke(thread);
    } catch (Throwable t) {
      return false;
    }
  }

  private static final MethodHandle lookupIsVirtual() {
    try {
      return MethodHandles.lookup()
          .findVirtual(Thread.class, "isVirtual", MethodType.methodType(boolean.class));
    } catch (NoSuchMethodException | IllegalAccessException e) {
      return null;
    }
  }

  private static final MethodHandle lookupId() {
    MethodHandle threadIdHandle = lookupThreadId();
    return threadIdHandle != null ? threadIdHandle : lookupGetId();
  }

  private static final MethodHandle lookupThreadId() {
    try {
      return MethodHandles.lookup()
          .findVirtual(Thread.class, "threadId", MethodType.methodType(long.class));
    } catch (NoSuchMethodException | IllegalAccessException e) {
      return null;
    }
  }

  private static final MethodHandle lookupGetId() {
    try {
      return MethodHandles.lookup()
          .findVirtual(Thread.class, "getId", MethodType.methodType(long.class));
    } catch (NoSuchMethodException | IllegalAccessException e) {
      return null;
    }
  }
}

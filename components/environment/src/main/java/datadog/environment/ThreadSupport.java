package datadog.environment;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Helper class for working with {@link Thread}s.
 *
 * <p>Uses feature detection and provides static helpers to work with different versions of Java.
 *
 * <p>This class is designed to use {@link MethodHandle}s that constant propagate to minimize the
 * overhead.
 */
public final class ThreadSupport {
  static final MethodHandle THREAD_ID_MH = findThreadIdMethodHandle();
  static final MethodHandle IS_VIRTUAL_MH = findIsVirtualMethodHandle();
  static final MethodHandle NEW_VIRTUAL_THREAD_PER_TASK_EXECUTOR_MH =
      findNewVirtualThreadPerTaskExecutorMethodHandle();

  private ThreadSupport() {}

  /**
   * Provides the best identifier available for the current {@link Thread}. Uses {@link
   * Thread#threadId()} on 19+ or {@link Thread#getId()} on older JVMs.
   *
   * @return The best identifier available for the current {@link Thread}.
   */
  public static long threadId() {
    return threadId(Thread.currentThread());
  }

  /**
   * Provides the best identifier available for the given {@link Thread}. Uses {@link
   * Thread#threadId()} on 19+ or {@link Thread#getId()} on older JVMs.
   *
   * @return The best identifier available for the given {@link Thread}.
   */
  public static long threadId(Thread thread) {
    if (THREAD_ID_MH != null) {
      try {
        return (long) THREAD_ID_MH.invoke(thread);
      } catch (Throwable ignored) {
      }
    }
    return thread.getId();
  }

  /**
   * Checks whether virtual threads are supported on this JVM.
   *
   * @return {@code true} if virtual threads are supported, {@code false} otherwise.
   */
  public static boolean supportsVirtualThreads() {
    return (IS_VIRTUAL_MH != null);
  }

  /**
   * Checks whether the current thread is a virtual thread.
   *
   * @return {@code true} if the current thread is a virtual thread, {@code false} otherwise.
   */
  public static boolean isVirtual() {
    // IS_VIRTUAL_MH will constant propagate -- then dead code eliminate -- and inline as needed
    return IS_VIRTUAL_MH != null && isVirtual(Thread.currentThread());
  }

  /**
   * Checks whether the given thread is a virtual thread.
   *
   * @param thread The thread to check.
   * @return {@code true} if the given thread is virtual, {@code false} otherwise.
   */
  public static boolean isVirtual(Thread thread) {
    // IS_VIRTUAL_MH will constant propagate -- then dead code eliminate -- and inline as needed
    if (IS_VIRTUAL_MH != null) {
      try {
        return (boolean) IS_VIRTUAL_MH.invoke(thread);
      } catch (Throwable ignored) {
      }
    }
    return false;
  }

  /**
   * Returns the virtual thread per task executor if available.
   *
   * @return The virtual thread per task executor if available wrapped into an {@link Optional}, or
   *     {@link Optional#empty()} otherwise.
   */
  public static Optional<ExecutorService> newVirtualThreadPerTaskExecutor() {
    if (NEW_VIRTUAL_THREAD_PER_TASK_EXECUTOR_MH != null) {
      try {
        ExecutorService executorService =
            (ExecutorService) NEW_VIRTUAL_THREAD_PER_TASK_EXECUTOR_MH.invoke();
        return Optional.of(executorService);
      } catch (Throwable ignored) {
      }
    }
    return Optional.empty();
  }

  private static MethodHandle findThreadIdMethodHandle() {
    if (JavaVirtualMachine.isJavaVersionAtLeast(19)) {
      try {
        return MethodHandles.lookup()
            .findVirtual(Thread.class, "threadId", MethodType.methodType(long.class));
      } catch (Throwable ignored) {
        return null;
      }
    }
    return null;
  }

  private static MethodHandle findIsVirtualMethodHandle() {
    if (JavaVirtualMachine.isJavaVersionAtLeast(21)) {
      try {
        return MethodHandles.lookup()
            .findVirtual(Thread.class, "isVirtual", MethodType.methodType(boolean.class));
      } catch (Throwable ignored) {
      }
    }
    return null;
  }

  private static MethodHandle findNewVirtualThreadPerTaskExecutorMethodHandle() {
    if (JavaVirtualMachine.isJavaVersionAtLeast(21)) {
      try {
        return MethodHandles.lookup()
            .findStatic(
                Executors.class,
                "newVirtualThreadPerTaskExecutor",
                MethodType.methodType(ExecutorService.class));
      } catch (Throwable ignored) {
      }
    }
    return null;
  }
}

// Copyright 2026 Datadog, Inc.
package com.datadoghq.profiler;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Compatibility bridge for TaskBlock hooks added to newer ddprof artifacts.
 *
 * <p>This class deliberately lives in the profiler package: that gives its lookup access to the
 * package-private park hooks without making those hooks public. Hook names are resolved from
 * strings so the agent remains binary-compatible with older ddprof artifacts. Resolved method
 * handles are cached and invoked directly on the hot path.
 *
 * <p>A hook whose native implementation is missing from the loaded library throws a {@link
 * LinkageError} on first use; the bridge then turns every hook off for the life of the JVM instead
 * of propagating the error into instrumented application code.
 */
public final class TaskBlockBridge {
  /** Reported when TaskBlock is requested but the loaded ddprof lacks one of the hook methods. */
  public static final String MISSING_HOOKS =
      "TaskBlock instrumentation is enabled but the loaded ddprof library does not provide the"
          + " TaskBlock hooks; Java-level TaskBlock events are disabled.";

  /** Reported when a TaskBlock hook exists but its native implementation fails to link. */
  public static final String MISSING_NATIVE =
      "A ddprof TaskBlock hook has no native implementation in the loaded ddprof library;"
          + " Java-level TaskBlock events are disabled.";

  private static final MethodHandle PARK_ENTER = findVirtual("parkEnter", boolean.class);
  private static final MethodHandle PARK_EXIT =
      findVirtual("parkExit", void.class, long.class, long.class);
  private static final MethodHandle BEGIN_TASK_BLOCK = findVirtual("beginTaskBlock", long.class);
  private static final MethodHandle END_TASK_BLOCK =
      findVirtual("endTaskBlock", boolean.class, long.class, long.class, long.class);

  // The native library is loaded once per JVM, so one linkage failure disables every bridge.
  private static volatile boolean linkageFailed;
  private static final AtomicBoolean REPORTED = new AtomicBoolean();

  private final JavaProfiler profiler;
  private final Consumer<String> unsupportedReporter;

  /**
   * Creates a bridge bound to the loaded profiler singleton.
   *
   * @param unsupportedReporter receives {@link #MISSING_HOOKS} or {@link #MISSING_NATIVE} at most
   *     once per JVM across all bridge instances
   */
  public TaskBlockBridge(JavaProfiler profiler, Consumer<String> unsupportedReporter) {
    this.profiler = profiler;
    this.unsupportedReporter = unsupportedReporter;
  }

  /** Returns whether both native park hooks are available. */
  public boolean hasParkSupport() {
    return !linkageFailed && PARK_ENTER != null && PARK_EXIT != null;
  }

  /** Returns whether the synchronous paired TaskBlock API is available. */
  public boolean hasSynchronousTaskBlockSupport() {
    return !linkageFailed && BEGIN_TASK_BLOCK != null && END_TASK_BLOCK != null;
  }

  /**
   * Reports {@link #MISSING_HOOKS} unless both the park hooks and the synchronous API resolved.
   * Callers invoke this only when TaskBlock instrumentation is enabled.
   */
  public void reportIfUnsupported() {
    if (!hasParkSupport() || !hasSynchronousTaskBlockSupport()) {
      report(MISSING_HOOKS);
    }
  }

  /** Invokes the native park-entry hook when it is available. */
  public boolean parkEnter() {
    if (!hasParkSupport()) {
      return false;
    }
    try {
      return (boolean) PARK_ENTER.invokeExact(profiler);
    } catch (LinkageError error) {
      disable();
      return false;
    } catch (Throwable throwable) {
      throw propagate(throwable);
    }
  }

  /** Invokes the native park-exit hook when it is available. */
  public void parkExit(long blocker, long unblockingSpanId) {
    if (!hasParkSupport()) {
      return;
    }
    try {
      PARK_EXIT.invokeExact(profiler, blocker, unblockingSpanId);
    } catch (LinkageError error) {
      disable();
    } catch (Throwable throwable) {
      throw propagate(throwable);
    }
  }

  /** Begins a synchronous TaskBlock interval, returning {@code 0} when unsupported. */
  public long beginTaskBlock() {
    if (!hasSynchronousTaskBlockSupport()) {
      return 0L;
    }
    try {
      return (long) BEGIN_TASK_BLOCK.invokeExact(profiler);
    } catch (LinkageError error) {
      disable();
      return 0L;
    } catch (Throwable throwable) {
      throw propagate(throwable);
    }
  }

  /** Ends a synchronous TaskBlock interval when the paired API is available. */
  public boolean endTaskBlock(long token, long blocker, long unblockingSpanId) {
    if (!hasSynchronousTaskBlockSupport()) {
      return false;
    }
    try {
      return (boolean) END_TASK_BLOCK.invokeExact(profiler, token, blocker, unblockingSpanId);
    } catch (LinkageError error) {
      disable();
      return false;
    } catch (Throwable throwable) {
      throw propagate(throwable);
    }
  }

  private void disable() {
    linkageFailed = true;
    report(MISSING_NATIVE);
  }

  private void report(String reason) {
    if (REPORTED.compareAndSet(false, true)) {
      unsupportedReporter.accept(reason);
    }
  }

  private static MethodHandle findVirtual(
      String methodName, Class<?> returnType, Class<?>... parameterTypes) {
    return findVirtual(JavaProfiler.class, methodName, returnType, parameterTypes);
  }

  static MethodHandle findVirtual(
      Class<?> targetClass, String methodName, Class<?> returnType, Class<?>... parameterTypes) {
    try {
      return MethodHandles.lookup()
          .findVirtual(targetClass, methodName, MethodType.methodType(returnType, parameterTypes));
    } catch (NoSuchMethodException | IllegalAccessException ignored) {
      return null;
    }
  }

  private static RuntimeException propagate(Throwable throwable) {
    if (throwable instanceof RuntimeException) {
      return (RuntimeException) throwable;
    }
    if (throwable instanceof Error) {
      throw (Error) throwable;
    }
    return new IllegalStateException(
        "Unexpected checked exception from ddprof TaskBlock hook", throwable);
  }
}

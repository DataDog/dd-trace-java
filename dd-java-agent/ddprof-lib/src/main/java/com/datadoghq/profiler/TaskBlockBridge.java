// Copyright 2026 Datadog, Inc.
package com.datadoghq.profiler;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Compatibility bridge for TaskBlock hooks added to newer ddprof artifacts.
 *
 * <p>This class deliberately lives in the profiler package: that gives its lookup access to the
 * package-private park hooks without making those hooks public. Hook names are resolved from
 * strings so the agent remains binary-compatible with older ddprof artifacts. Resolved method
 * handles are cached and invoked directly on the hot path.
 */
public final class TaskBlockBridge {
  private static final MethodHandle PARK_ENTER = findVirtual("parkEnter", void.class);
  private static final MethodHandle PARK_EXIT =
      findVirtual("parkExit", void.class, long.class, long.class);
  private static final MethodHandle BEGIN_TASK_BLOCK = findVirtual("beginTaskBlock", long.class);
  private static final MethodHandle END_TASK_BLOCK =
      findVirtual("endTaskBlock", boolean.class, long.class, long.class, long.class);

  private final JavaProfiler profiler;

  /** Creates a bridge bound to the loaded profiler singleton. */
  public TaskBlockBridge(JavaProfiler profiler) {
    this.profiler = profiler;
  }

  /** Returns whether both native park hooks are available. */
  public boolean hasParkSupport() {
    return PARK_ENTER != null && PARK_EXIT != null;
  }

  /** Returns whether the synchronous paired TaskBlock API is available. */
  public boolean hasSynchronousTaskBlockSupport() {
    return BEGIN_TASK_BLOCK != null && END_TASK_BLOCK != null;
  }

  /** Invokes the native park-entry hook when it is available. */
  public boolean parkEnter() {
    if (!hasParkSupport()) {
      return false;
    }
    try {
      PARK_ENTER.invokeExact(profiler);
      return true;
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
    } catch (Throwable throwable) {
      throw propagate(throwable);
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

// Copyright 2026 Datadog, Inc.
package datadog.trace.bootstrap.instrumentation.java.concurrent;

import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.ProfilingContextIntegration;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.TimeUnit;

/** Helper for synchronously bracketing untraced {@code Thread.sleep} intervals. */
public final class TaskBlockHelper {
  private TaskBlockHelper() {}

  static ProfilingContextIntegration profiling() {
    try {
      return AgentTracer.get().getProfilingContext();
    } catch (Throwable ignored) {
      return null;
    }
  }

  static long begin(ProfilingContextIntegration profiling) {
    if (profiling == null) {
      return 0L;
    }
    try {
      return profiling.beginTaskBlock();
    } catch (Throwable ignored) {
      return 0L;
    }
  }

  static void finish(ProfilingContextIntegration profiling, long token) {
    if (profiling == null || token == 0L) {
      return;
    }
    try {
      profiling.endTaskBlock(token, 0L, 0L);
    } catch (Throwable ignored) {
    }
  }

  /** Brackets {@link Thread#sleep(long)} with a synchronous TaskBlock interval. */
  public static void sleep(long millis) throws InterruptedException {
    sleep(profiling(), millis);
  }

  static void sleep(ProfilingContextIntegration profiling, long millis)
      throws InterruptedException {
    long token = begin(profiling);
    try {
      Thread.sleep(millis);
    } finally {
      finish(profiling, token);
    }
  }

  /** Brackets {@link Thread#sleep(long, int)} with a synchronous TaskBlock interval. */
  public static void sleep(long millis, int nanos) throws InterruptedException {
    sleep(profiling(), millis, nanos);
  }

  static void sleep(ProfilingContextIntegration profiling, long millis, int nanos)
      throws InterruptedException {
    long token = begin(profiling);
    try {
      Thread.sleep(millis, nanos);
    } finally {
      finish(profiling, token);
    }
  }

  /** Brackets {@link TimeUnit#sleep(long)} with a synchronous TaskBlock interval. */
  public static void sleep(TimeUnit unit, long timeout) throws InterruptedException {
    sleep(profiling(), unit, timeout);
  }

  static void sleep(ProfilingContextIntegration profiling, TimeUnit unit, long timeout)
      throws InterruptedException {
    long token = begin(profiling);
    try {
      unit.sleep(timeout);
    } finally {
      finish(profiling, token);
    }
  }

  /**
   * Brackets {@code Thread.sleep(Duration)} without linking {@code Duration} on JDKs where that
   * overload is unavailable.
   */
  public static void sleepDuration(Object duration) throws InterruptedException {
    ProfilingContextIntegration profiling = profiling();
    long token = begin(profiling);
    try {
      invokeDurationSleep(duration);
    } finally {
      finish(profiling, token);
    }
  }

  private static void invokeDurationSleep(Object duration) throws InterruptedException {
    MethodHandle sleep = DurationSleep.SLEEP;
    if (sleep == null) {
      throw new NoSuchMethodError("java.lang.Thread.sleep(java.time.Duration)");
    }
    try {
      sleep.invokeExact(duration);
    } catch (InterruptedException error) {
      throw error;
    } catch (RuntimeException | Error error) {
      throw error;
    } catch (Throwable error) {
      throw new IllegalStateException("Unexpected checked exception from Thread.sleep", error);
    }
  }

  private static final class DurationSleep {
    private static final MethodHandle SLEEP = findSleep();

    private static MethodHandle findSleep() {
      try {
        Class<?> duration = Class.forName("java.time.Duration", false, null);
        return MethodHandles.publicLookup()
            .findStatic(Thread.class, "sleep", MethodType.methodType(void.class, duration))
            .asType(MethodType.methodType(void.class, Object.class));
      } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException ignored) {
        return null;
      }
    }
  }
}

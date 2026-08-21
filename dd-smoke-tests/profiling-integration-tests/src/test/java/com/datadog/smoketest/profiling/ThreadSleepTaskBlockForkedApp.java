// Copyright 2026 Datadog, Inc.
package com.datadog.smoketest.profiling;

import datadog.trace.api.Trace;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;

/** Forked workload covering spanless, active-context, and virtual {@code Thread.sleep} calls. */
public final class ThreadSleepTaskBlockForkedApp {
  public static final String SPANLESS_PLATFORM_THREAD = "threadsleep-spanless";
  public static final String ACTIVE_PLATFORM_THREAD = "threadsleep-active";
  public static final String VIRTUAL_THREAD = "threadsleep-virtual";
  public static final String CROSS_ROTATION_THREAD = "threadsleep-cross-rotation";
  public static final long CROSS_ROTATION_SLEEP_MILLIS = 2500L;

  // Iteration count and duration are generous on purpose: the wall-clock sampler only emits a
  // TaskBlock event for a bracket it happens to observe, so a longer aggregate blocking window
  // drives the probability of a zero-event run down instead of relying on a single short bracket.
  private static final int SLEEP_ITERATIONS = 40;
  private static final long SLEEP_MILLIS = 75L;
  private static final long PROFILING_STARTUP_DELAY_MILLIS = 1500L;

  private ThreadSleepTaskBlockForkedApp() {}

  public static void main(String[] args) throws Exception {
    ThreadSleepTaskBlockForkedApp app = new ThreadSleepTaskBlockForkedApp();
    Thread.sleep(PROFILING_STARTUP_DELAY_MILLIS);
    Thread spanless = new Thread(app::runSpanlessSleeps, SPANLESS_PLATFORM_THREAD);
    Thread active = new Thread(app::runActiveSpanSleeps, ACTIVE_PLATFORM_THREAD);
    spanless.start();
    active.start();
    Thread virtual = app.startVirtualWorkerIfSupported();
    Thread crossRotation = new Thread(app::runCrossRotationSleep, CROSS_ROTATION_THREAD);
    crossRotation.start();
    spanless.join();
    active.join();
    crossRotation.join();
    if (virtual != null) {
      virtual.join();
    }
    Thread.sleep(1500L);
  }

  private void runCrossRotationSleep() {
    try {
      Thread.sleep(CROSS_ROTATION_SLEEP_MILLIS);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(error);
    }
  }

  private void runSpanlessSleeps() {
    for (int i = 0; i < SLEEP_ITERATIONS; i++) {
      sleep();
    }
  }

  @Trace
  private void runActiveSpanSleeps() {
    for (int i = 0; i < SLEEP_ITERATIONS; i++) {
      sleep();
    }
  }

  private Thread startVirtualWorkerIfSupported() throws Exception {
    Method startVirtualThread;
    try {
      startVirtualThread = Thread.class.getMethod("startVirtualThread", Runnable.class);
    } catch (NoSuchMethodException ignored) {
      return null;
    }
    CountDownLatch named = new CountDownLatch(1);
    Runnable task =
        () -> {
          await(named);
          runSpanlessSleeps();
        };
    try {
      Thread thread = (Thread) startVirtualThread.invoke(null, task);
      thread.setName(VIRTUAL_THREAD);
      named.countDown();
      return thread;
    } catch (InvocationTargetException error) {
      throw new IllegalStateException("Unable to start virtual sleep worker", error.getCause());
    }
  }

  private static void sleep() {
    try {
      Thread.sleep(SLEEP_MILLIS);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(error);
    }
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(error);
    }
  }
}

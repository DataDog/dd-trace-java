// Copyright 2026 Datadog, Inc.
package com.datadog.smoketest.profiling;

import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.Tracer;
import io.opentracing.util.GlobalTracer;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;

/** Forked workload covering spanless, active-context, and virtual {@code Thread.sleep} calls. */
public final class ThreadSleepTaskBlockForkedApp {
  public static final String SPANLESS_PLATFORM_THREAD = "threadsleep-spanless";
  public static final String ACTIVE_PLATFORM_THREAD = "threadsleep-active";
  public static final String VIRTUAL_THREAD = "threadsleep-virtual";

  private static final int SLEEP_ITERATIONS = 20;
  private static final long SLEEP_MILLIS = 50L;

  private final Tracer tracer;

  private ThreadSleepTaskBlockForkedApp(Tracer tracer) {
    this.tracer = tracer;
  }

  public static void main(String[] args) throws Exception {
    ThreadSleepTaskBlockForkedApp app = new ThreadSleepTaskBlockForkedApp(GlobalTracer.get());
    Thread spanless = new Thread(app::runSpanlessSleeps, SPANLESS_PLATFORM_THREAD);
    Thread active = new Thread(app::runActiveSpanSleeps, ACTIVE_PLATFORM_THREAD);
    spanless.start();
    active.start();
    Thread virtual = app.startVirtualWorkerIfSupported();
    spanless.join();
    active.join();
    if (virtual != null) {
      virtual.join();
    }
    Thread.sleep(1500L);
  }

  private void runSpanlessSleeps() {
    for (int i = 0; i < SLEEP_ITERATIONS; i++) {
      sleep();
    }
  }

  private void runActiveSpanSleeps() {
    for (int i = 0; i < SLEEP_ITERATIONS; i++) {
      Span span = tracer.buildSpan("threadsleep.active").start();
      try (Scope ignored = tracer.activateSpan(span)) {
        sleep();
      } finally {
        span.finish();
      }
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

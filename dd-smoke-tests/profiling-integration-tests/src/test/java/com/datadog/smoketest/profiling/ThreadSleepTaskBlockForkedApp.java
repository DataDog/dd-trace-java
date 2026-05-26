package com.datadog.smoketest.profiling;

import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.Tracer;
import io.opentracing.util.GlobalTracer;

public final class ThreadSleepTaskBlockForkedApp {
  private static final int SLEEP_ITERATIONS = 20;
  private static final long LONG_SLEEP_MILLIS = 50L;
  private static final long SUB_THRESHOLD_NANOS = 1L;

  public static void main(String[] args) throws Exception {
    ThreadSleepTaskBlockForkedApp app = new ThreadSleepTaskBlockForkedApp(GlobalTracer.get());
    app.runActiveSpanSleeps();
    app.runSpanlessSleeps();
    app.runSubThresholdSleeps();
    Thread.sleep(1500);
  }

  private final Tracer tracer;

  private ThreadSleepTaskBlockForkedApp(Tracer tracer) {
    this.tracer = tracer;
  }

  private void runActiveSpanSleeps() throws InterruptedException {
    for (int i = 0; i < SLEEP_ITERATIONS; i++) {
      Span span = tracer.buildSpan("threadsleep.active").start();
      try (Scope scope = tracer.activateSpan(span)) {
        Thread.sleep(LONG_SLEEP_MILLIS);
      } finally {
        span.finish();
      }
    }
  }

  private void runSpanlessSleeps() throws InterruptedException {
    for (int i = 0; i < SLEEP_ITERATIONS; i++) {
      Thread.sleep(LONG_SLEEP_MILLIS);
    }
  }

  private void runSubThresholdSleeps() throws InterruptedException {
    for (int i = 0; i < SLEEP_ITERATIONS; i++) {
      Span span = tracer.buildSpan("threadsleep.short").start();
      try (Scope scope = tracer.activateSpan(span)) {
        Thread.sleep(0L, (int) SUB_THRESHOLD_NANOS);
      } finally {
        span.finish();
      }
    }
  }
}

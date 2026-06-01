package com.datadog.smoketest.profiling;

import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.Tracer;
import io.opentracing.util.GlobalTracer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

public final class BlockingMixForkedApp {
  private static final String OP_SLEEP = "blockingmix.sleep";
  private static final String OP_PARK = "blockingmix.park";
  private static final String OP_SYNC = "blockingmix.sync";

  private static final int SLEEP_ITERATIONS = 20;
  private static final int PARK_ITERATIONS = 20;
  private static final int SYNC_ITERATIONS = 20;
  private static final long PARK_NANOS = 50_000_000L;
  private static final long SLEEP_MILLIS = 50L;
  private static final long SYNC_HOLD_MILLIS = 50L;

  public static void main(String[] args) throws Exception {
    BlockingMixForkedApp app = new BlockingMixForkedApp(GlobalTracer.get());
    app.runSleeps();
    app.runParks();
    app.runSyncContention();
    Thread.sleep(1500);
  }

  private final Tracer tracer;

  private BlockingMixForkedApp(Tracer tracer) {
    this.tracer = tracer;
  }

  private void runSleeps() throws InterruptedException {
    for (int i = 0; i < SLEEP_ITERATIONS; i++) {
      Span span = tracer.buildSpan(OP_SLEEP).start();
      try (Scope scope = tracer.activateSpan(span)) {
        Thread.sleep(SLEEP_MILLIS);
      } finally {
        span.finish();
      }
    }
  }

  private void runParks() {
    for (int i = 0; i < PARK_ITERATIONS; i++) {
      Span span = tracer.buildSpan(OP_PARK).start();
      try (Scope scope = tracer.activateSpan(span)) {
        long deadline = System.nanoTime() + PARK_NANOS;
        long remaining;
        while ((remaining = deadline - System.nanoTime()) > 0) {
          LockSupport.parkNanos(remaining);
        }
      } finally {
        span.finish();
      }
    }
  }

  private void runSyncContention() throws InterruptedException {
    final Object lock = new Object();
    for (int i = 0; i < SYNC_ITERATIONS; i++) {
      final CountDownLatch holderHasLock = new CountDownLatch(1);
      final CountDownLatch holderMayRelease = new CountDownLatch(1);
      Thread holder =
          new Thread(
              () -> {
                synchronized (lock) {
                  holderHasLock.countDown();
                  try {
                    holderMayRelease.await(2, TimeUnit.SECONDS);
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  }
                }
              },
              "blockingmix-holder-" + i);
      holder.setDaemon(true);
      holder.start();
      holderHasLock.await();

      Span span = tracer.buildSpan(OP_SYNC).start();
      try (Scope scope = tracer.activateSpan(span)) {
        Thread.sleep(SYNC_HOLD_MILLIS / 2);
        new Thread(
                () -> {
                  try {
                    Thread.sleep(SYNC_HOLD_MILLIS / 2);
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  }
                  holderMayRelease.countDown();
                })
            .start();
        synchronized (lock) {
          // Acquire after contention.
        }
      } finally {
        span.finish();
      }
      holder.join();
    }
  }
}

// Copyright 2026 Datadog, Inc.
package com.datadog.smoketest.profiling;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

public final class BlockingMixForkedApp {
  private static final String THREAD_SLEEP = "blockingmix-sleep";
  private static final String THREAD_PARK = "blockingmix-park";
  private static final String THREAD_SYNC = "blockingmix-sync";

  private static final int SLEEP_ITERATIONS = 20;
  private static final int PARK_ITERATIONS = 20;
  private static final int SYNC_ITERATIONS = 20;
  private static final long PARK_NANOS = 50_000_000L;
  private static final long SLEEP_MILLIS = 50L;
  private static final long SYNC_HOLD_MILLIS = 50L;

  public static void main(String[] args) throws Exception {
    BlockingMixForkedApp app = new BlockingMixForkedApp();
    runWorker(THREAD_SLEEP, app::runSleeps);
    Thread.sleep(1500);
    runWorker(THREAD_PARK, app::runParks);
    runWorker(THREAD_SYNC, app::runSyncContention);
    Thread.sleep(1500);
  }

  private BlockingMixForkedApp() {}

  private static void runWorker(String name, InterruptibleTask task) throws Exception {
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Thread worker =
        new Thread(
            () -> {
              try {
                task.run();
              } catch (Throwable throwable) {
                failure.set(throwable);
              }
            },
            name);
    worker.start();
    worker.join();
    Throwable throwable = failure.get();
    if (throwable != null) {
      if (throwable instanceof Exception) {
        throw (Exception) throwable;
      }
      if (throwable instanceof Error) {
        throw (Error) throwable;
      }
      throw new RuntimeException(throwable);
    }
  }

  private void runSleeps() throws InterruptedException {
    for (int i = 0; i < SLEEP_ITERATIONS; i++) {
      Thread.sleep(SLEEP_MILLIS);
    }
  }

  private void runParks() {
    for (int i = 0; i < PARK_ITERATIONS; i++) {
      long deadline = System.nanoTime() + PARK_NANOS;
      long remaining;
      while ((remaining = deadline - System.nanoTime()) > 0) {
        LockSupport.parkNanos(remaining);
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

      new Thread(
              () -> {
                try {
                  Thread.sleep(SYNC_HOLD_MILLIS);
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
                holderMayRelease.countDown();
              })
          .start();
      synchronized (lock) {
        // Acquire after contention.
      }
      holder.join();
    }
  }

  @FunctionalInterface
  private interface InterruptibleTask {
    void run() throws Exception;
  }
}

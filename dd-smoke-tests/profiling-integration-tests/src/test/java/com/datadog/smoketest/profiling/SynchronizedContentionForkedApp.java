// Copyright 2026 Datadog, Inc.
package com.datadog.smoketest.profiling;

import java.util.concurrent.CountDownLatch;

/** Forked workload for native monitor-contention TaskBlock smoke coverage. */
public final class SynchronizedContentionForkedApp {
  private static final int REPETITIONS = 10;
  private static final Object BLOCK_LOCK = new Object();

  public static void main(final String[] args) throws Exception {
    SynchronizedContentionForkedApp app = new SynchronizedContentionForkedApp();
    for (int i = 0; i < REPETITIONS; i++) {
      app.runBlockScenario();
      app.runInstanceMethodScenario();
      app.runStaticMethodScenario();
    }
    Thread.sleep(1500);
  }

  private final InstanceLockTarget instanceTarget = new InstanceLockTarget();

  private void runBlockScenario() throws Exception {
    CountDownLatch holderIn = new CountDownLatch(1);
    Thread holder =
        new Thread(
            () -> {
              synchronized (BLOCK_LOCK) {
                holderIn.countDown();
                sleepWhileHoldingMonitor();
              }
            },
            "sync-block-holder");
    holder.start();
    holderIn.await();

    Thread contender =
        new Thread(
            () -> {
              synchronized (BLOCK_LOCK) {
                // entry-queue wait is the TaskBlock interval
              }
            },
            "sync-block-spanless");
    contender.start();
    contender.join();
    holder.join();
  }

  private void runInstanceMethodScenario() throws Exception {
    CountDownLatch holderIn = new CountDownLatch(1);
    Thread holder = new Thread(() -> instanceTarget.hold(holderIn), "sync-instance-holder");
    holder.start();
    holderIn.await();

    Thread contender = new Thread(instanceTarget::contend, "sync-instance-spanless");
    contender.start();
    contender.join();
    holder.join();
  }

  private void runStaticMethodScenario() throws Exception {
    CountDownLatch holderIn = new CountDownLatch(1);
    Thread holder = new Thread(() -> StaticLockTarget.hold(holderIn), "sync-static-holder");
    holder.start();
    holderIn.await();

    Thread contender = new Thread(StaticLockTarget::contend, "sync-static-spanless");
    contender.start();
    contender.join();
    holder.join();
  }

  private static void sleepWhileHoldingMonitor() {
    try {
      Thread.sleep(50L);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  static final class InstanceLockTarget {
    synchronized void hold(final CountDownLatch in) {
      in.countDown();
      sleepWhileHoldingMonitor();
    }

    synchronized void contend() {}
  }

  static final class StaticLockTarget {
    static synchronized void hold(final CountDownLatch in) {
      in.countDown();
      sleepWhileHoldingMonitor();
    }

    static synchronized void contend() {}
  }
}

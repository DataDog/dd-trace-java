package com.datadog.smoketest.profiling;

import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.Tracer;
import io.opentracing.util.GlobalTracer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class SynchronizedContentionForkedApp {
  private static final int REPETITIONS = 5;
  private static final Object BLOCK_LOCK = new Object();

  public static void main(final String[] args) throws Exception {
    SynchronizedContentionForkedApp app = new SynchronizedContentionForkedApp(GlobalTracer.get());
    for (int i = 0; i < REPETITIONS; i++) {
      app.runBlockScenario();
      app.runInstanceMethodScenario();
      app.runStaticMethodScenario();
    }
    Thread.sleep(1500);
  }

  private final Tracer tracer;
  private final InstanceLockTarget instanceTarget = new InstanceLockTarget();

  private SynchronizedContentionForkedApp(final Tracer tracer) {
    this.tracer = tracer;
  }

  private void runBlockScenario() throws Exception {
    CountDownLatch holderIn = new CountDownLatch(1);
    CountDownLatch holderOut = new CountDownLatch(1);
    Thread holder =
        new Thread(
            () -> {
              synchronized (BLOCK_LOCK) {
                holderIn.countDown();
                try {
                  holderOut.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
              }
            },
            "sync-block-holder");
    holder.setDaemon(true);
    holder.start();
    holderIn.await();

    Span span = tracer.buildSpan("sync.block").start();
    try (Scope scope = tracer.activateSpan(span)) {
      synchronized (BLOCK_LOCK) {
        // entry-queue wait is the TaskBlock interval
      }
    } finally {
      span.finish();
    }
    holderOut.countDown();
    holder.join();
  }

  private void runInstanceMethodScenario() throws Exception {
    CountDownLatch holderIn = new CountDownLatch(1);
    CountDownLatch holderOut = new CountDownLatch(1);
    Thread holder =
        new Thread(() -> instanceTarget.hold(holderIn, holderOut), "sync-instance-holder");
    holder.setDaemon(true);
    holder.start();
    holderIn.await();

    Span span = tracer.buildSpan("sync.instance-method").start();
    try (Scope scope = tracer.activateSpan(span)) {
      instanceTarget.contend();
    } finally {
      span.finish();
    }
    holderOut.countDown();
    holder.join();
  }

  private void runStaticMethodScenario() throws Exception {
    CountDownLatch holderIn = new CountDownLatch(1);
    CountDownLatch holderOut = new CountDownLatch(1);
    Thread holder =
        new Thread(() -> StaticLockTarget.hold(holderIn, holderOut), "sync-static-holder");
    holder.setDaemon(true);
    holder.start();
    holderIn.await();

    Span span = tracer.buildSpan("sync.static-method").start();
    try (Scope scope = tracer.activateSpan(span)) {
      StaticLockTarget.contend();
    } finally {
      span.finish();
    }
    holderOut.countDown();
    holder.join();
  }

  static final class InstanceLockTarget {
    synchronized void hold(final CountDownLatch in, final CountDownLatch out) {
      in.countDown();
      try {
        out.await(2, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    synchronized void contend() {}
  }

  static final class StaticLockTarget {
    static synchronized void hold(final CountDownLatch in, final CountDownLatch out) {
      in.countDown();
      try {
        out.await(2, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    static synchronized void contend() {}
  }
}

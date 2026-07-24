// Copyright 2026 Datadog, Inc.
package com.datadog.smoketest.profiling;

import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.Tracer;
import io.opentracing.util.GlobalTracer;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/** Forked workload for platform and virtual-thread LockSupport TaskBlock smoke coverage. */
public final class LockSupportTaskBlockForkedApp {
  public static final String SPANLESS_PLATFORM_THREAD = "locksupport-spanless-platform";
  public static final String ACTIVE_PLATFORM_THREAD = "locksupport-active-platform";
  public static final String VIRTUAL_THREAD = "locksupport-virtual";
  public static final String SPANLESS_BLOCKER_MARKER = "LOCKSUPPORT_SPANLESS_BLOCKER=";
  public static final String ACTIVE_BLOCKER_MARKER = "LOCKSUPPORT_ACTIVE_BLOCKER=";
  public static final String VIRTUAL_BLOCKER_MARKER = "LOCKSUPPORT_VIRTUAL_BLOCKER=";

  private static final int PARK_ITERATIONS = 20;
  private static final int ATTRIBUTED_PARK_ITERATIONS = 10;
  private static final long PARK_NANOS = TimeUnit.MILLISECONDS.toNanos(40);
  private static final long ATTRIBUTED_PARK_NANOS = TimeUnit.SECONDS.toNanos(5);
  private static final long PROFILING_STARTUP_DELAY_MILLIS = 1500L;
  private static final long ATTRIBUTED_UNPARK_DELAY_MILLIS = 100L;
  private static final Object SPANLESS_BLOCKER = new Object();
  private static final Object ACTIVE_BLOCKER = new Object();
  private static final Object VIRTUAL_BLOCKER = new Object();

  private LockSupportTaskBlockForkedApp() {}

  /** Runs the smoke workload. */
  public static void main(String[] args) throws Exception {
    printBlocker(SPANLESS_BLOCKER_MARKER, SPANLESS_BLOCKER);
    printBlocker(ACTIVE_BLOCKER_MARKER, ACTIVE_BLOCKER);
    printBlocker(VIRTUAL_BLOCKER_MARKER, VIRTUAL_BLOCKER);
    Tracer tracer = GlobalTracer.get();
    Thread.sleep(PROFILING_STARTUP_DELAY_MILLIS);
    runSpanlessPlatformWorker(tracer);
    runActivePlatformWorker(tracer);
    runVirtualWorker();
    Thread.sleep(1500L);
  }

  private static void runSpanlessPlatformWorker(Tracer tracer) throws Exception {
    CountDownLatch[] ready = new CountDownLatch[ATTRIBUTED_PARK_ITERATIONS];
    CountDownLatch[] finished = new CountDownLatch[ATTRIBUTED_PARK_ITERATIONS];
    for (int i = 0; i < ATTRIBUTED_PARK_ITERATIONS; i++) {
      ready[i] = new CountDownLatch(1);
      finished[i] = new CountDownLatch(1);
    }
    Thread worker =
        new Thread(
            () -> {
              for (int i = 0; i < ATTRIBUTED_PARK_ITERATIONS; i++) {
                ready[i].countDown();
                LockSupport.parkNanos(SPANLESS_BLOCKER, ATTRIBUTED_PARK_NANOS);
                finished[i].countDown();
              }
            },
            SPANLESS_PLATFORM_THREAD);
    worker.start();
    for (int i = 0; i < ATTRIBUTED_PARK_ITERATIONS; i++) {
      if (!ready[i].await(5, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Spanless platform worker did not start park " + i);
      }
      awaitParked(worker, i);
      Thread.sleep(ATTRIBUTED_UNPARK_DELAY_MILLIS);
      Span span = tracer.buildSpan("locksupport.unparker").start();
      try (Scope ignored = tracer.activateSpan(span)) {
        LockSupport.unpark(worker);
      } finally {
        span.finish();
      }
      if (!finished[i].await(5, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Spanless platform worker did not finish park " + i);
      }
    }
    join(worker);
  }

  private static void runActivePlatformWorker(Tracer tracer) throws Exception {
    Thread worker =
        new Thread(
            () -> {
              Span span = tracer.buildSpan("locksupport.active").start();
              try (Scope ignored = tracer.activateSpan(span)) {
                for (int i = 0; i < PARK_ITERATIONS; i++) {
                  LockSupport.parkNanos(ACTIVE_BLOCKER, PARK_NANOS);
                }
              } finally {
                span.finish();
              }
            },
            ACTIVE_PLATFORM_THREAD);
    worker.start();
    join(worker);
  }

  private static void runVirtualWorker() throws Exception {
    Method startVirtualThread;
    try {
      startVirtualThread = Thread.class.getMethod("startVirtualThread", Runnable.class);
    } catch (NoSuchMethodException unsupported) {
      System.out.println("Virtual threads are unavailable; skipping virtual-thread workload");
      return;
    }
    Runnable workload =
        () -> {
          Thread.currentThread().setName(VIRTUAL_THREAD);
          for (int i = 0; i < PARK_ITERATIONS; i++) {
            LockSupport.parkNanos(VIRTUAL_BLOCKER, PARK_NANOS);
          }
        };
    Thread worker;
    try {
      worker = (Thread) startVirtualThread.invoke(null, workload);
    } catch (InvocationTargetException error) {
      Throwable cause = error.getCause();
      if (cause instanceof Exception) {
        throw (Exception) cause;
      }
      if (cause instanceof Error) {
        throw (Error) cause;
      }
      throw new IllegalStateException(cause);
    }
    join(worker);
  }

  private static void join(Thread worker) throws InterruptedException {
    worker.join(TimeUnit.SECONDS.toMillis(10));
    if (worker.isAlive()) {
      throw new IllegalStateException("Worker did not finish: " + worker.getName());
    }
  }

  private static void awaitParked(Thread worker, int iteration) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < deadline) {
      Thread.State state = worker.getState();
      if (state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING) {
        return;
      }
      Thread.sleep(5L);
    }
    throw new IllegalStateException(
        "Spanless platform worker did not park " + iteration + "; state=" + worker.getState());
  }

  private static void printBlocker(String marker, Object blocker) {
    System.out.println(marker + Integer.toUnsignedLong(System.identityHashCode(blocker)));
  }
}

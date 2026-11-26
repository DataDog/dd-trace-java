package datadog.trace.civisibility.coverage.probe;

import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.civisibility.coverage.probe.fixtures.ConcurrentWorkload;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;

/** Runs the workers of {@link ConcurrentWorkload}, and waits for the threads that run them. */
final class ConcurrentWorkloads {

  /** The prefix of the classes a {@link FixtureLoader} defines for the workload. */
  static final String FIXTURES = ConcurrentWorkload.class.getName();

  private ConcurrentWorkloads() {}

  /** The reference lines of each worker run alone, in worker order. */
  static List<Map<String, BitSet>> referenceLinesOfEachWorker() {
    List<Map<String, BitSet>> lines = new ArrayList<>();
    for (int worker = 0; worker < ConcurrentWorkload.WORKERS; worker++) {
      ReferenceLines.drain();
      run(new FixtureLoader(ReferenceLines::instrument, FIXTURES), worker);
      lines.add(ReferenceLines.drain());
    }
    return lines;
  }

  /** Runs a worker of the workload that a loader defined. */
  static void run(ClassLoader loader, int worker) {
    try {
      Class<?> workload = Class.forName(ConcurrentWorkload.class.getName(), true, loader);
      workload.getMethod("run", int.class).invoke(null, worker);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
  }

  /** Runs a worker on a new thread and waits for the thread to end. */
  static void runOnNewThread(ClassLoader loader, int worker) {
    Thread thread = new Thread(() -> run(loader, worker));
    thread.start();
    join(thread);
  }

  static void await(CyclicBarrier barrier) {
    try {
      barrier.await(30, TimeUnit.SECONDS);
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  static void await(CountDownLatch latch) {
    try {
      assertTrue(latch.await(30, TimeUnit.SECONDS));
    } catch (InterruptedException e) {
      throw new AssertionError(e);
    }
  }

  static void join(Thread thread) {
    try {
      thread.join(TimeUnit.SECONDS.toMillis(30));
    } catch (InterruptedException e) {
      throw new AssertionError(e);
    }
  }
}

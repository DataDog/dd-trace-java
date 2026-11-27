package datadog.trace.civisibility.coverage.probe;

import static datadog.trace.civisibility.coverage.probe.ConcurrentWorkloads.FIXTURES;
import static datadog.trace.civisibility.coverage.probe.ConcurrentWorkloads.join;
import static datadog.trace.civisibility.coverage.probe.ConcurrentWorkloads.run;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import datadog.trace.api.civisibility.coverage.ProbeRecorder;
import datadog.trace.civisibility.coverage.probe.fixtures.ConcurrentWorkload;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * The recorder keeps one set of probe arrays per thread, also for a thread whose thread locals are
 * erased while it lives, so the threads it registers are bounded by the threads that live.
 */
class ProbeThreadRegistryTest {

  /** Set by each task on its worker, to tell when the JDK erases the worker's thread locals. */
  private static final ThreadLocal<Boolean> MARK = new ThreadLocal<>();

  private static final AtomicInteger ERASURES = new AtomicInteger();

  /** Times the common pool's workers run tasks and then wait for more. */
  private static final int IDLE_ROUNDS = 2_000;

  /** Runs the idle rounds for this many seconds instead, for a longer check by hand. */
  private static final String IDLE_SECONDS_VARIABLE = "PROBE_REGISTRY_TEST_IDLE_SECONDS";

  @Test
  void aThreadWhoseThreadLocalsAreErasedKeepsOneSetOfArrays() {
    FixtureLoader loader = new FixtureLoader(null, FIXTURES);
    long created = ProbeRecorder.getCreatedThreadProbesCount();
    int registered = ProbeRecorder.getRegisteredThreadProbesCount();
    Thread thread =
        new Thread(() -> {
          for (int i = 0; i < 100; i++) {
            run(loader, i % ConcurrentWorkload.WORKERS);
            RecorderThreadLocals.erase();
          }
        });
    thread.start();
    join(thread);

    assertEquals(1, ProbeRecorder.getCreatedThreadProbesCount() - created);
    assertTrue(ProbeRecorder.getRegisteredThreadProbesCount() <= registered + 1);
  }

  /**
   * On JDK 24 and later, a worker of the common pool erases its thread locals each time it waits
   * for a task.
   */
  @Test
  void commonPoolWorkersKeepOneSetOfArraysWhileTheyGoIdle() {
    FixtureLoader loader = new FixtureLoader(null, FIXTURES);
    Set<Thread> workers = ConcurrentHashMap.newKeySet();
    // joining a task can run it on this thread, which then needs no arrays of its own
    run(loader, 0);
    long created = ProbeRecorder.getCreatedThreadProbesCount();
    int registered = ProbeRecorder.getRegisteredThreadProbesCount();

    int rounds = 0;
    while (rounds < 10) {
      runAndGoIdle(loader, workers);
      rounds++;
    }
    assumeTrue(
        ERASURES.get() > 0, "the common pool's workers keep their thread locals on this JDK");
    String idleSeconds = System.getenv(IDLE_SECONDS_VARIABLE);
    if (idleSeconds != null) {
      long deadline = System.nanoTime() + SECONDS.toNanos(Long.parseLong(idleSeconds));
      while (System.nanoTime() < deadline) {
        runAndGoIdle(loader, workers);
        rounds++;
      }
    } else {
      while (rounds < IDLE_ROUNDS) {
        runAndGoIdle(loader, workers);
        rounds++;
      }
    }

    long createdDuringRounds = ProbeRecorder.getCreatedThreadProbesCount() - created;
    int registeredAfterRounds = ProbeRecorder.getRegisteredThreadProbesCount();
    System.out.println(
        rounds
            + " idle rounds of the common pool on Java "
            + System.getProperty("java.version")
            + ": "
            + workers.size()
            + " workers ran tasks, "
            + createdDuringRounds
            + " threads given probe arrays, "
            + ERASURES.get()
            + " times a worker's thread locals were erased, threads registered "
            + registered
            + " before and "
            + registeredAfterRounds
            + " after, threads alive "
            + Thread.getAllStackTraces().size());
    assertTrue(createdDuringRounds <= workers.size());
    assertTrue(registeredAfterRounds <= registered + workers.size());
  }

  /**
   * Gives each worker of the common pool a task, and waits until every worker that ran a task waits
   * for the next one.
   */
  private static void runAndGoIdle(ClassLoader loader, Set<Thread> workers) {
    ForkJoinPool pool = ForkJoinPool.commonPool();
    List<ForkJoinTask<?>> tasks = new ArrayList<>();
    for (int task = 0; task < pool.getParallelism(); task++) {
      int worker = task % ConcurrentWorkload.WORKERS;
      tasks.add(
          pool.submit(() -> {
            Thread thread = Thread.currentThread();
            if (thread instanceof ForkJoinWorkerThread) {
              if (MARK.get() == null && !workers.add(thread)) {
                ERASURES.incrementAndGet();
              }
              MARK.set(Boolean.TRUE);
            }
            run(loader, worker);
          }));
    }
    for (ForkJoinTask<?> task : tasks) {
      task.join();
    }
    long deadline = System.nanoTime() + SECONDS.toNanos(30);
    for (Thread worker : workers) {
      while (!isWaitingOrDead(worker)) {
        if (System.nanoTime() > deadline) {
          fail("the worker " + worker.getName() + " did not go idle");
        }
        Thread.yield();
      }
    }
  }

  private static boolean isWaitingOrDead(Thread thread) {
    Thread.State state = thread.getState();
    return state == Thread.State.WAITING
        || state == Thread.State.TIMED_WAITING
        || state == Thread.State.TERMINATED;
  }
}

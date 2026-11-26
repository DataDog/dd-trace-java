package datadog.trace.civisibility.coverage.probe;

import static datadog.trace.civisibility.coverage.probe.TestWindow.sharedLines;
import static datadog.trace.civisibility.coverage.probe.TestWindow.union;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.civisibility.coverage.TestPreparationWindow;
import datadog.trace.civisibility.coverage.probe.fixtures.ConcurrentWorkload;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Windows open at the same time, on different threads or nested on one, get exactly the lines of
 * the code they own; code that no window owns goes to Shared. A thread created while a window owns
 * the code of its creator runs its code for that window while it is open, unless it is a pool
 * worker.
 */
class ProbeConcurrencyTest {

  private static final String FIXTURES = ConcurrentWorkload.class.getName();

  /** Reference lines of each worker run alone. */
  private static final List<Map<String, BitSet>> WORKER_LINES = new ArrayList<>();

  @BeforeAll
  static void recordReferenceLines() throws Exception {
    for (int worker = 0; worker < ConcurrentWorkload.WORKERS; worker++) {
      ReferenceLines.drain();
      run(new FixtureLoader(ReferenceLines::instrument, FIXTURES), worker);
      WORKER_LINES.add(ReferenceLines.drain());
    }
  }

  @Test
  void windowsOpenOnTwoThreadsGetOnlyTheirOwnLines() throws Exception {
    FixtureLoader loader = new FixtureLoader(null, FIXTURES);
    CountDownLatch firstOpen = new CountDownLatch(1);
    CyclicBarrier allOpen = new CyclicBarrier(3);
    CyclicBarrier allRan = new CyclicBarrier(3);
    AtomicReference<Map<String, BitSet>> first = new AtomicReference<>();
    AtomicReference<Map<String, BitSet>> second = new AtomicReference<>();

    Thread firstThread =
        new Thread(() -> first.set(runInWindow(loader, 0, firstOpen::countDown, allOpen, allRan)));
    Thread secondThread =
        new Thread(() -> {
          await(firstOpen);
          second.set(runInWindow(loader, 1, () -> {}, allOpen, allRan));
        });
    firstThread.start();
    secondThread.start();
    await(allOpen);
    run(loader, 2); // no window is open on this thread
    await(allRan);
    firstThread.join(TimeUnit.SECONDS.toMillis(30));
    secondThread.join(TimeUnit.SECONDS.toMillis(30));

    assertEquals(WORKER_LINES.get(0), first.get());
    assertEquals(WORKER_LINES.get(1), second.get());
    assertEquals(WORKER_LINES.get(2), sharedLines(loader));
  }

  @Test
  void aWindowNestedOnAThreadTakesItsCodeUntilItCloses() throws Exception {
    FixtureLoader loader = new FixtureLoader(null, FIXTURES);
    TestWindow outer = new TestWindow();
    Map<String, BitSet> outerLines;
    Map<String, BitSet> innerLines;
    try {
      run(loader, 0);
      TestWindow inner = new TestWindow();
      try {
        run(loader, 1);
      } finally {
        innerLines = inner.closeAndCollect();
      }
      run(loader, 2);
    } finally {
      outerLines = outer.closeAndCollect();
    }

    assertEquals(WORKER_LINES.get(1), innerLines);
    assertEquals(union(WORKER_LINES.get(0), WORKER_LINES.get(2)), outerLines);
    assertTrue(sharedLines(loader).isEmpty());
  }

  @Test
  void windowsOpenedOneAfterAnotherOnAThreadEachGetTheirOwnLines() throws Exception {
    FixtureLoader loader = new FixtureLoader(null, FIXTURES);
    TestWindow primary = new TestWindow();
    List<Map<String, BitSet>> lines = new ArrayList<>();
    try {
      Thread thread =
          new Thread(() -> {
            for (int worker = 1; worker < ConcurrentWorkload.WORKERS; worker++) {
              TestWindow window = new TestWindow();
              try {
                run(loader, worker);
              } finally {
                lines.add(window.closeAndCollect());
              }
            }
          });
      thread.start();
      thread.join(TimeUnit.SECONDS.toMillis(30));
    } finally {
      assertTrue(primary.closeAndCollect().isEmpty());
    }

    assertEquals(WORKER_LINES.subList(1, ConcurrentWorkload.WORKERS), lines);
    assertTrue(sharedLines(loader).isEmpty());
  }

  @Test
  void codeRunWhileATestIsPreparedGoesToTheTestWhenItStarts() throws Exception {
    FixtureLoader loader = new FixtureLoader(null, FIXTURES);
    TestPreparationWindow.enable();
    TestPreparationWindow.open(null);
    run(loader, 0);
    TestWindow test = new TestWindow(TestPreparationWindow.detach());
    Map<String, BitSet> testLines;
    try {
      run(loader, 1);
    } finally {
      testLines = test.closeAndCollect();
    }

    assertEquals(union(WORKER_LINES.get(0), WORKER_LINES.get(1)), testLines);
    assertNull(TestPreparationWindow.detach());
    assertTrue(sharedLines(loader).isEmpty());
  }

  @Test
  void codeRunWhileATestIsPreparedGoesToSharedWhenTheTestDoesNotStart() throws Exception {
    FixtureLoader loader = new FixtureLoader(null, FIXTURES);
    TestPreparationWindow.enable();
    TestPreparationWindow.open(null);
    run(loader, 0);
    // the next test's preparation gives the window of a test that never started to Shared
    TestPreparationWindow.open(null);
    run(loader, 1);
    // and so does a test skipped before it starts
    TestPreparationWindow.discard();

    assertEquals(union(WORKER_LINES.get(0), WORKER_LINES.get(1)), sharedLines(loader));
  }

  @Test
  void aThreadThatDiesWithNoWindowOpenLeavesItsLinesToShared() throws Exception {
    FixtureLoader loader = new FixtureLoader(null, FIXTURES);
    Thread thread = new Thread(() -> run(loader, 0));
    thread.start();
    thread.join(TimeUnit.SECONDS.toMillis(30));

    assertEquals(WORKER_LINES.get(0), sharedLines(loader));
  }

  @Test
  void threadsCreatedWhileAWindowIsOpenRunTheirCodeForIt() throws Exception {
    FixtureLoader loader = new FixtureLoader(null, FIXTURES);
    TestWindow window = new TestWindow();
    Map<String, BitSet> windowLines;
    try {
      // the thread creates another before it runs any instrumented code itself
      Thread thread =
          new Thread(() -> {
            Thread nested = new Thread(() -> run(loader, 1));
            nested.start();
            join(nested);
            run(loader, 0);
          });
      thread.start();
      join(thread);
    } finally {
      windowLines = window.closeAndCollect();
    }

    assertEquals(union(WORKER_LINES.get(0), WORKER_LINES.get(1)), windowLines);
    assertTrue(sharedLines(loader).isEmpty());
  }

  @Test
  void aThreadCreatedWhileAWindowIsOpenRunsForSharedOnceTheWindowCloses() throws Exception {
    FixtureLoader loader = new FixtureLoader(null, FIXTURES);
    CountDownLatch ran = new CountDownLatch(1);
    CountDownLatch closed = new CountDownLatch(1);
    TestWindow window = new TestWindow();
    Thread thread;
    Map<String, BitSet> windowLines;
    try {
      thread =
          new Thread(() -> {
            run(loader, 0);
            ran.countDown();
            await(closed);
            run(loader, 1);
          });
      thread.start();
      await(ran);
    } finally {
      windowLines = window.closeAndCollect();
    }
    closed.countDown();
    join(thread);

    assertEquals(WORKER_LINES.get(0), windowLines);
    assertEquals(WORKER_LINES.get(1), sharedLines(loader));
  }

  @Test
  void poolWorkersCreatedWhileAWindowIsOpenAreHandedNoWindow() throws Exception {
    FixtureLoader loader = new FixtureLoader(null, FIXTURES);
    ExecutorService executor = Executors.newSingleThreadExecutor();
    ForkJoinPool forkJoinPool = new ForkJoinPool(1);
    TestWindow window = new TestWindow();
    // created while the window is open, as are the workers of the pools below
    Timer timer = new Timer(true);
    Map<String, BitSet> windowLines;
    try {
      // the tasks carry no test's context, so the code they run is Shared's
      executor.submit(() -> run(loader, 0)).get(30, TimeUnit.SECONDS);
      forkJoinPool.submit(() -> run(loader, 1)).get(30, TimeUnit.SECONDS);
      CountDownLatch timerRan = new CountDownLatch(1);
      timer.schedule(
          new TimerTask() {
            @Override
            public void run() {
              ProbeConcurrencyTest.run(loader, 2);
              timerRan.countDown();
            }
          },
          0);
      await(timerRan);
    } finally {
      windowLines = window.closeAndCollect();
      executor.shutdown();
      forkJoinPool.shutdown();
      timer.cancel();
    }

    assertTrue(windowLines.isEmpty());
    assertEquals(
        union(WORKER_LINES.get(0), WORKER_LINES.get(1), WORKER_LINES.get(2)), sharedLines(loader));
  }

  private static Map<String, BitSet> runInWindow(
      ClassLoader loader,
      int worker,
      Runnable opened,
      CyclicBarrier allOpen,
      CyclicBarrier allRan) {
    TestWindow window = new TestWindow();
    Map<String, BitSet> lines;
    try {
      opened.run();
      await(allOpen);
      run(loader, worker);
      await(allRan);
    } finally {
      lines = window.closeAndCollect();
    }
    return lines;
  }

  private static void run(ClassLoader loader, int worker) {
    try {
      Class<?> workload = Class.forName(ConcurrentWorkload.class.getName(), true, loader);
      workload.getMethod("run", int.class).invoke(null, worker);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
  }

  private static void await(CyclicBarrier barrier) {
    try {
      barrier.await(30, TimeUnit.SECONDS);
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  private static void await(CountDownLatch latch) {
    try {
      assertTrue(latch.await(30, TimeUnit.SECONDS));
    } catch (InterruptedException e) {
      throw new AssertionError(e);
    }
  }

  private static void join(Thread thread) {
    try {
      thread.join(TimeUnit.SECONDS.toMillis(30));
    } catch (InterruptedException e) {
      throw new AssertionError(e);
    }
  }
}

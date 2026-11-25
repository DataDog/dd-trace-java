package datadog.trace.civisibility.coverage.probe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.civisibility.coverage.CoverageWindow;
import datadog.trace.api.civisibility.coverage.InstrumentedClass;
import datadog.trace.api.civisibility.coverage.ProbeRecorder;
import datadog.trace.api.civisibility.coverage.TestPreparationWindow;
import datadog.trace.civisibility.coverage.probe.fixtures.ConcurrentWorkload;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Windows open at the same time, on different threads or nested on one, get exactly the lines of
 * the code they own; code that no window owns goes to Shared.
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

    // the first window is primary and uses the global table, the second one a table of its own
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
  void aWindowThatReusesATableStartsEmpty() throws Exception {
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
    TestPreparationWindow.open();
    run(loader, 0);
    List<CoverageWindow.ClassHits> preparation = TestPreparationWindow.take();
    TestWindow test = new TestWindow();
    Map<String, BitSet> testLines;
    try {
      run(loader, 1);
    } finally {
      testLines = test.closeAndCollect();
    }

    assertEquals(WORKER_LINES.get(0), lines(preparation));
    assertEquals(WORKER_LINES.get(1), testLines);
    assertTrue(TestPreparationWindow.take().isEmpty());
    assertTrue(sharedLines(loader).isEmpty());
  }

  @Test
  void codeRunWhileATestIsPreparedGoesToSharedWhenTheTestDoesNotStart() throws Exception {
    FixtureLoader loader = new FixtureLoader(null, FIXTURES);
    TestPreparationWindow.enable();
    TestPreparationWindow.open();
    run(loader, 0);
    // the next test's preparation gives the window of a test that never started to Shared
    TestPreparationWindow.open();
    run(loader, 1);
    // and so does a test skipped before it starts
    TestPreparationWindow.discard();

    assertEquals(union(WORKER_LINES.get(0), WORKER_LINES.get(1)), sharedLines(loader));
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

  private static Map<String, BitSet> sharedLines(ClassLoader loader) {
    Map<String, BitSet> lines = new HashMap<>();
    for (int classId = 0; classId < ProbeRecorder.getClassCount(); classId++) {
      InstrumentedClass instrumentedClass = ProbeRecorder.getInstrumentedClass(classId);
      if (instrumentedClass != null && instrumentedClass.loader() == loader) {
        BitSet classLines = new BitSet();
        if (ProbeRecorder.addSharedLines(classId, classLines)) {
          lines.computeIfAbsent(sourcePath(instrumentedClass), path -> new BitSet()).or(classLines);
        }
      }
    }
    return lines;
  }

  private static Map<String, BitSet> union(Map<String, BitSet> a, Map<String, BitSet> b) {
    Map<String, BitSet> union = new HashMap<>();
    for (Map<String, BitSet> lines : Arrays.asList(a, b)) {
      for (Map.Entry<String, BitSet> entry : lines.entrySet()) {
        union.computeIfAbsent(entry.getKey(), path -> new BitSet()).or(entry.getValue());
      }
    }
    return union;
  }

  private static String sourcePath(InstrumentedClass instrumentedClass) {
    return ReferenceLines.sourcePath(instrumentedClass.name(), instrumentedClass.sourceFile());
  }

  private static Map<String, BitSet> lines(List<CoverageWindow.ClassHits> classHits) {
    Map<String, BitSet> lines = new HashMap<>();
    for (CoverageWindow.ClassHits hits : classHits) {
      BitSet classLines = new BitSet();
      hits.instrumentedClass.addLines(hits.probes, classLines);
      lines
          .computeIfAbsent(sourcePath(hits.instrumentedClass), path -> new BitSet())
          .or(classLines);
    }
    return lines;
  }

  private static final class TestWindow extends CoverageWindow {
    Map<String, BitSet> closeAndCollect() {
      return lines(close());
    }
  }
}

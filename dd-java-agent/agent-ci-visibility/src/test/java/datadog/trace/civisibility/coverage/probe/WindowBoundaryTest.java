package datadog.trace.civisibility.coverage.probe;

import static datadog.trace.civisibility.coverage.probe.TestWindow.sharedLines;
import static datadog.trace.civisibility.coverage.probe.TestWindow.union;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.civisibility.coverage.TestPreparationWindow;
import datadog.trace.civisibility.coverage.probe.fixtures.CalledWork;
import datadog.trace.civisibility.coverage.probe.fixtures.WindowSpanningWorkload;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * A method that keeps running while windows open and close keeps the owner it started with: its
 * lines go to that owner, or to Shared once that owner's window has reported. The methods it calls
 * after a change start with the owner after the change.
 */
class WindowBoundaryTest {

  private static final String WORKLOAD = WindowSpanningWorkload.class.getName();

  private static final String[] FIXTURES = {WORKLOAD, CalledWork.class.getName()};

  private static final String WORKLOAD_FILE =
      ReferenceLines.sourcePath(WORKLOAD.replace('.', '/'), "WindowSpanningWorkload.java");

  @Test
  void aMethodRunningWhenItsWindowClosesGivesItsLinesToShared() {
    List<Map<String, BitSet>> reference = referenceAround();

    FixtureLoader loader = new FixtureLoader(null, FIXTURES);
    TestWindow window = new TestWindow();
    AtomicReference<Map<String, BitSet>> windowLines = new AtomicReference<>();
    around(loader, () -> windowLines.set(window.closeAndCollect()));

    assertEquals(withoutWorkload(reference.get(0)), windowLines.get());
    assertEquals(union(reference.get(1), workloadOnly(reference.get(0))), sharedLines(loader));
  }

  @Test
  void aMethodRunningWhenAWindowOpensKeepsItsLinesInShared() {
    List<Map<String, BitSet>> reference = referenceAround();

    FixtureLoader loader = new FixtureLoader(null, FIXTURES);
    AtomicReference<TestWindow> window = new AtomicReference<>();
    around(loader, () -> window.set(new TestWindow()));
    Map<String, BitSet> windowLines = window.get().closeAndCollect();

    assertEquals(withoutWorkload(reference.get(1)), windowLines);
    assertEquals(union(reference.get(0), workloadOnly(reference.get(1))), sharedLines(loader));
  }

  @Test
  void aMethodRunningWhileANestedWindowOpensAndClosesKeepsItsLinesInItsWindow() {
    List<Map<String, BitSet>> reference = referenceAround();

    FixtureLoader loader = new FixtureLoader(null, FIXTURES);
    TestWindow outer = new TestWindow();
    AtomicReference<Map<String, BitSet>> innerLines = new AtomicReference<>();
    around(loader, () -> innerLines.set(new TestWindow().closeAndCollect()));
    Map<String, BitSet> outerLines = outer.closeAndCollect();

    assertTrue(innerLines.get().isEmpty());
    assertEquals(union(reference.get(0), reference.get(1)), outerLines);
    assertTrue(sharedLines(loader).isEmpty());
  }

  @Test
  void aLoopAcrossWindowsGivesEachWindowTheMethodsItsIterationsCalled() {
    int windows = 3;
    List<Map<String, BitSet>> reference = new ArrayList<>();
    Runnable drain = () -> reference.add(ReferenceLines.drain());
    Runnable[] referenceSteps = new Runnable[windows + 1];
    for (int step = 0; step <= windows; step++) {
      referenceSteps[step] = drain;
    }
    referenceBetween(referenceSteps, reference);

    FixtureLoader loader = new FixtureLoader(null, FIXTURES);
    List<Map<String, BitSet>> windowLines = new ArrayList<>();
    AtomicReference<TestWindow> open = new AtomicReference<>();
    Runnable[] steps = new Runnable[windows + 1];
    for (int step = 0; step <= windows; step++) {
      boolean last = step == windows;
      steps[step] =
          () -> {
            if (open.get() != null) {
              windowLines.add(open.get().closeAndCollect());
            }
            open.set(last ? null : new TestWindow());
          };
    }
    between(loader, steps);

    List<Map<String, BitSet>> expectedWindowLines = new ArrayList<>();
    for (Map<String, BitSet> lines : reference.subList(1, windows + 1)) {
      expectedWindowLines.add(withoutWorkload(lines));
    }
    assertEquals(expectedWindowLines, windowLines);
    // the loop started with no window open, and so did the code before the first step and after
    // the last one
    Map<String, BitSet> all = union(reference);
    assertEquals(
        union(reference.get(0), reference.get(windows + 1), workloadOnly(all)),
        sharedLines(loader));
  }

  @Test
  void aMethodThatWaitsWhileWindowsOpenAndCloseKeepsItsLines() {
    List<Map<String, BitSet>> reference = new ArrayList<>();
    Runnable drain = () -> reference.add(ReferenceLines.drain());
    referenceBetween(new Runnable[] {drain, drain, drain}, reference);

    FixtureLoader loader = new FixtureLoader(null, FIXTURES);
    AtomicReference<TestWindow> window = new AtomicReference<>();
    List<Map<String, BitSet>> nestedLines = new ArrayList<>();
    AtomicReference<Map<String, BitSet>> windowLines = new AtomicReference<>();
    Runnable[] steps = {
      () -> window.set(new TestWindow()),
      () -> {
        // the loop waits for this step while more windows open and close than an array that no
        // method stored into after a change survives
        for (int i = 0; i < 5; i++) {
          nestedLines.add(new TestWindow().closeAndCollect());
        }
      },
      () -> windowLines.set(window.get().closeAndCollect())
    };
    between(loader, steps);

    for (Map<String, BitSet> lines : nestedLines) {
      assertTrue(lines.isEmpty());
    }
    Map<String, BitSet> windowed = union(reference.get(1), reference.get(2));
    assertEquals(withoutWorkload(windowed), windowLines.get());
    // the lines the loop runs after the last step are Shared's only if its array was kept
    assertEquals(
        union(reference.get(0), reference.get(3), workloadOnly(windowed)), sharedLines(loader));
  }

  @Test
  void aTestThatStartsDuringAMethodTakesTheWholeMethodFromItsPreparation() {
    List<Map<String, BitSet>> reference = referenceAround();

    FixtureLoader loader = new FixtureLoader(null, FIXTURES);
    TestPreparationWindow.enable();
    TestPreparationWindow.open();
    AtomicReference<TestWindow> test = new AtomicReference<>();
    around(loader, () -> test.set(new TestWindow(TestPreparationWindow.detach())));
    Map<String, BitSet> testLines = test.get().closeAndCollect();

    assertEquals(union(reference.get(0), reference.get(1)), testLines);
    assertTrue(sharedLines(loader).isEmpty());
  }

  /**
   * Runs {@link WindowSpanningWorkload#around} with reference probes.
   *
   * @return the lines that ran before its middle and those that ran after
   */
  private static List<Map<String, BitSet>> referenceAround() {
    List<Map<String, BitSet>> reference = new ArrayList<>();
    FixtureLoader loader = new FixtureLoader(ReferenceLines::instrument, FIXTURES);
    ReferenceLines.drain();
    around(loader, () -> reference.add(ReferenceLines.drain()));
    reference.add(ReferenceLines.drain());
    return reference;
  }

  /**
   * Runs {@link WindowSpanningWorkload#between} with reference probes; the steps add what ran
   * before them to {@code reference}, and this adds what ran after the last.
   */
  private static void referenceBetween(Runnable[] steps, List<Map<String, BitSet>> reference) {
    FixtureLoader loader = new FixtureLoader(ReferenceLines::instrument, FIXTURES);
    ReferenceLines.drain();
    between(loader, steps);
    reference.add(ReferenceLines.drain());
  }

  private static void around(ClassLoader loader, Runnable middle) {
    invoke(loader, "around", Runnable.class, middle);
  }

  private static void between(ClassLoader loader, Runnable[] steps) {
    invoke(loader, "between", Runnable[].class, steps);
  }

  private static void invoke(
      ClassLoader loader, String method, Class<?> parameterType, Object argument) {
    try {
      Class<?> workload = Class.forName(WORKLOAD, true, loader);
      workload.getMethod(method, parameterType).invoke(null, argument);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
  }

  /** The lines of the long-running methods. */
  private static Map<String, BitSet> workloadOnly(Map<String, BitSet> lines) {
    Map<String, BitSet> workload = new HashMap<>();
    BitSet workloadLines = lines.get(WORKLOAD_FILE);
    if (workloadLines != null) {
      workload.put(WORKLOAD_FILE, workloadLines);
    }
    return workload;
  }

  /** The lines of the methods they call. */
  private static Map<String, BitSet> withoutWorkload(Map<String, BitSet> lines) {
    Map<String, BitSet> called = union(lines);
    called.remove(WORKLOAD_FILE);
    return called;
  }
}

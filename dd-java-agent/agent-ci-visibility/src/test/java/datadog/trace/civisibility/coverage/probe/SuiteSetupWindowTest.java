package datadog.trace.civisibility.coverage.probe;

import static datadog.trace.civisibility.coverage.probe.TestWindow.sharedLines;
import static datadog.trace.civisibility.coverage.probe.TestWindow.union;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import datadog.trace.api.civisibility.coverage.SuiteSetupWindow;
import datadog.trace.api.civisibility.coverage.TestPreparationWindow;
import datadog.trace.civisibility.coverage.probe.fixtures.ConcurrentWorkload;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * A suite's setup, up to the start of its first child, is credited to every test in the suite,
 * nested suites included, and to Shared; what the suite runs after its first child started is not.
 */
class SuiteSetupWindowTest {

  private static final String FIXTURES = ConcurrentWorkload.class.getName();

  /** Reference lines of each worker run alone. */
  private static final List<Map<String, BitSet>> WORKER_LINES = new ArrayList<>();

  @BeforeAll
  static void recordReferenceLines() {
    for (int worker = 0; worker < ConcurrentWorkload.WORKERS; worker++) {
      ReferenceLines.drain();
      run(new FixtureLoader(ReferenceLines::instrument, FIXTURES), worker);
      WORKER_LINES.add(ReferenceLines.drain());
    }
    TestPreparationWindow.enable();
  }

  @Test
  void eachTestOfASuiteGetsItsSetupButNotItsTeardown() {
    FixtureLoader loader = new FixtureLoader(null, FIXTURES);
    Object suite = new Object();
    // a suite without setup of its own passes on the setup of the suite around it
    Object nested = new Object();
    SuiteSetupWindow.open(suite, null, true);
    run(loader, 0);
    SuiteSetupWindow.handOver(suite);
    SuiteSetupWindow.childStarts(suite);
    SuiteSetupWindow.open(nested, suite, false);
    SuiteSetupWindow.handOver(nested);

    List<Map<String, BitSet>> testLines = new ArrayList<>();
    for (int test = 0; test < 2; test++) {
      testLines.add(runTest(loader, nested, -1, 1));
    }
    SuiteSetupWindow.finish(nested);
    run(loader, 2);
    SuiteSetupWindow.finish(suite);

    Map<String, BitSet> expected = union(WORKER_LINES.get(0), WORKER_LINES.get(1));
    assertEquals(expected, testLines.get(0));
    assertEquals(expected, testLines.get(1));
    assertEquals(union(WORKER_LINES.get(0), WORKER_LINES.get(2)), sharedLines(loader));
  }

  @Test
  void aTestGetsTheSetupOfEverySuiteAroundIt() {
    FixtureLoader loader = new FixtureLoader(null, FIXTURES);
    Object suite = new Object();
    Object nested = new Object();
    SuiteSetupWindow.open(suite, null, true);
    run(loader, 0);
    SuiteSetupWindow.handOver(suite);
    SuiteSetupWindow.childStarts(suite);
    SuiteSetupWindow.open(nested, suite, true);
    run(loader, 1);
    SuiteSetupWindow.handOver(nested);

    Map<String, BitSet> testLines = runTest(loader, nested, -1, 2);
    SuiteSetupWindow.finish(nested);
    SuiteSetupWindow.finish(suite);

    assertEquals(union(WORKER_LINES.get(0), WORKER_LINES.get(1), WORKER_LINES.get(2)), testLines);
    assertEquals(union(WORKER_LINES.get(0), WORKER_LINES.get(1)), sharedLines(loader));
  }

  @Test
  void aSuiteThatStartsNoChildGivesItsLinesToSharedOnly() {
    FixtureLoader loader = new FixtureLoader(null, FIXTURES);
    Object suite = new Object();
    SuiteSetupWindow.open(suite, null, true);
    run(loader, 0);
    SuiteSetupWindow.finish(suite);

    TestPreparationWindow.open(suite);
    TestPreparationWindow preparation = TestPreparationWindow.detach();
    assertNull(preparation.suiteSetupHits());
    new TestWindow(preparation).closeAndCollect();
    assertEquals(WORKER_LINES.get(0), sharedLines(loader));
  }

  @Test
  void theFirstTestsPreparationIsSetupOfEveryTest() {
    FixtureLoader loader = new FixtureLoader(null, FIXTURES);
    Object suite = new Object();
    SuiteSetupWindow.open(suite, null, true);
    run(loader, 0);
    SuiteSetupWindow.handOver(suite);

    // the first test instance builds a fixture that later instances reuse
    Map<String, BitSet> firstTestLines = runTest(loader, suite, 1, -1);
    Map<String, BitSet> secondTestLines = runTest(loader, suite, -1, 2);
    SuiteSetupWindow.finish(suite);

    assertEquals(union(WORKER_LINES.get(0), WORKER_LINES.get(1)), firstTestLines);
    assertEquals(
        union(WORKER_LINES.get(0), WORKER_LINES.get(1), WORKER_LINES.get(2)), secondTestLines);
    assertEquals(union(WORKER_LINES.get(0), WORKER_LINES.get(1)), sharedLines(loader));
  }

  @Test
  void aTestOfAnotherSuiteThatRunsFirstEndsTheSetup() {
    FixtureLoader loader = new FixtureLoader(null, FIXTURES);
    Object suite = new Object();
    Object otherSuite = new Object();
    SuiteSetupWindow.open(suite, null, true);
    run(loader, 0);
    SuiteSetupWindow.handOver(suite);

    // with parallel execution, the thread can run another suite's test before a child of its own
    Map<String, BitSet> otherTestLines = runTest(loader, otherSuite, 1, -1);
    Map<String, BitSet> testLines = runTest(loader, suite, -1, 2);
    SuiteSetupWindow.finish(suite);

    assertEquals(WORKER_LINES.get(1), otherTestLines);
    assertEquals(union(WORKER_LINES.get(0), WORKER_LINES.get(2)), testLines);
    assertEquals(WORKER_LINES.get(0), sharedLines(loader));
  }

  /**
   * Runs a test of {@code suite} the way the JUnit 5 integration sees it: the test is prepared,
   * running {@code preparationWorker}, then starts and runs {@code worker} (-1 runs nothing).
   *
   * @return its own lines and the setup lines it is credited with
   */
  private static Map<String, BitSet> runTest(
      ClassLoader loader, Object suite, int preparationWorker, int worker) {
    if (!SuiteSetupWindow.coversPreparation(suite)) {
      TestPreparationWindow.open(suite);
    }
    if (preparationWorker >= 0) {
      run(loader, preparationWorker);
    }
    if (SuiteSetupWindow.childStarts(suite)) {
      TestPreparationWindow.open(suite);
    }
    TestPreparationWindow preparation = TestPreparationWindow.detach();
    TestWindow test = new TestWindow(preparation);
    Map<String, BitSet> ownLines;
    try {
      if (worker >= 0) {
        run(loader, worker);
      }
    } finally {
      ownLines = test.closeAndCollect();
    }
    List<Map<String, BitSet>> parts = new ArrayList<>();
    parts.add(ownLines);
    for (SuiteSetupWindow.Hits setup = preparation.suiteSetupHits();
        setup != null;
        setup = setup.outer) {
      parts.add(TestWindow.lines(setup.hits));
    }
    return union(parts);
  }

  private static void run(ClassLoader loader, int worker) {
    try {
      Class<?> workload = Class.forName(ConcurrentWorkload.class.getName(), true, loader);
      workload.getMethod("run", int.class).invoke(null, worker);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
  }
}

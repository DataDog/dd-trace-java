package datadog.trace.civisibility.coverage.probe;

import static datadog.trace.civisibility.coverage.probe.ConcurrentWorkloads.FIXTURES;
import static datadog.trace.civisibility.coverage.probe.ConcurrentWorkloads.join;
import static datadog.trace.civisibility.coverage.probe.ConcurrentWorkloads.referenceLinesOfEachWorker;
import static datadog.trace.civisibility.coverage.probe.ConcurrentWorkloads.run;
import static datadog.trace.civisibility.coverage.probe.TestWindow.sharedLines;
import static datadog.trace.civisibility.coverage.probe.TestWindow.union;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * A thread whose thread locals are erased while it lives, as the JDK erases those of some pool
 * workers each time they go idle, keeps its probe arrays: what it runs afterwards still reaches its
 * owner.
 */
@ExtendWith(NoDroppedEntries.class)
class ProbeThreadLocalErasureTest {

  /** Reference lines of each worker run alone. */
  private static final List<Map<String, BitSet>> WORKER_LINES = new ArrayList<>();

  @BeforeAll
  static void setUp() {
    WORKER_LINES.addAll(referenceLinesOfEachWorker());
    // as in a test JVM, the test thread opens the first window: the threads below are not primary
    new TestWindow().closeAndCollect();
  }

  @Test
  void aWindowOpenOnTheThreadKeepsItsLines() {
    FixtureLoader loader = new FixtureLoader(null, FIXTURES);
    AtomicReference<Map<String, BitSet>> lines = new AtomicReference<>();
    runOnNewThread(() -> {
      TestWindow window = new TestWindow();
      run(loader, 0);
      RecorderThreadLocals.erase();
      run(loader, 1);
      lines.set(window.closeAndCollect());
    });

    assertEquals(union(WORKER_LINES.get(0), WORKER_LINES.get(1)), lines.get());
    assertTrue(sharedLines(loader).isEmpty());
  }

  @Test
  void aThreadHandedAWindowKeepsGivingItItsLines() {
    FixtureLoader loader = new FixtureLoader(null, FIXTURES);
    TestWindow window = new TestWindow();
    Map<String, BitSet> lines;
    try {
      runOnNewThread(() -> {
        run(loader, 0);
        RecorderThreadLocals.erase();
        run(loader, 1);
      });
    } finally {
      lines = window.closeAndCollect();
    }

    assertEquals(union(WORKER_LINES.get(0), WORKER_LINES.get(1)), lines);
    assertTrue(sharedLines(loader).isEmpty());
  }

  /** Runs a task on a new thread, which the current thread creates, and waits for it to end. */
  private static void runOnNewThread(Runnable task) {
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Thread thread =
        new Thread(() -> {
          try {
            task.run();
          } catch (Throwable e) {
            failure.set(e);
          }
        });
    thread.start();
    join(thread);
    assertNull(failure.get());
  }
}

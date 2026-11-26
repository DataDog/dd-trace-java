package datadog.trace.civisibility.coverage.probe;

import static datadog.trace.civisibility.coverage.probe.ConcurrentWorkloads.FIXTURES;
import static datadog.trace.civisibility.coverage.probe.ConcurrentWorkloads.join;
import static datadog.trace.civisibility.coverage.probe.ConcurrentWorkloads.referenceLinesOfEachWorker;
import static datadog.trace.civisibility.coverage.probe.TestWindow.sharedLines;
import static datadog.trace.test.util.GCUtils.awaitGC;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.ref.WeakReference;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * The recorder keeps what a thread hit after the thread ends, but not the thread: a finished thread
 * and its task can be collected, and the lines the thread ran still go to the test it ran them for.
 */
@ExtendWith(NoDroppedEntries.class)
class ProbeFinishedThreadTest {

  @Test
  void theLinesOfACollectedThreadGoToItsTest() throws InterruptedException {
    List<Map<String, BitSet>> workerLines = referenceLinesOfEachWorker();
    FixtureLoader loader = new FixtureLoader(null, FIXTURES);
    TestWindow window = new TestWindow();
    Map<String, BitSet> lines;
    try {
      Collectable finished = runOnFinishedThread(loader, 0);
      awaitGC(finished.thread, 30, SECONDS);
      awaitGC(finished.payload, 30, SECONDS);
    } finally {
      lines = window.closeAndCollect();
    }

    assertEquals(workerLines.get(0), lines);
    assertTrue(sharedLines(loader).isEmpty());
  }

  /** What a finished thread leaves that nothing should keep. */
  private static final class Collectable {
    final WeakReference<Thread> thread;
    final WeakReference<Object> payload;

    Collectable(Thread thread, Object payload) {
      this.thread = new WeakReference<>(thread);
      this.payload = new WeakReference<>(payload);
    }
  }

  /**
   * Runs a worker on a new thread, created inside the open window, whose task holds a payload, and
   * waits for the thread to end.
   */
  private static Collectable runOnFinishedThread(ClassLoader loader, int worker) {
    Object payload = new Object();
    Thread thread = new Thread(new TaskWithPayload(loader, worker, payload));
    thread.start();
    join(thread);
    assertFalse(thread.isAlive());
    return new Collectable(thread, payload);
  }

  /** A task that holds an object, as a task can hold what a test waits to see collected. */
  private static final class TaskWithPayload implements Runnable {
    private final ClassLoader loader;
    private final int worker;
    private final Object payload;

    TaskWithPayload(ClassLoader loader, int worker, Object payload) {
      this.loader = loader;
      this.worker = worker;
      this.payload = payload;
    }

    @Override
    public void run() {
      assertNotNull(payload);
      ConcurrentWorkloads.run(loader, worker);
    }
  }
}

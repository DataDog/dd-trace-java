package datadog.trace.civisibility.coverage.probe;

import static datadog.trace.civisibility.coverage.probe.ConcurrentWorkloads.FIXTURES;
import static datadog.trace.civisibility.coverage.probe.ConcurrentWorkloads.await;
import static datadog.trace.civisibility.coverage.probe.ConcurrentWorkloads.referenceLinesOfEachWorker;
import static datadog.trace.civisibility.coverage.probe.ConcurrentWorkloads.run;
import static datadog.trace.civisibility.coverage.probe.ConcurrentWorkloads.runOnNewThread;
import static datadog.trace.civisibility.coverage.probe.TestWindow.sharedLines;
import static datadog.trace.civisibility.coverage.probe.TestWindow.union;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.context.ContextContinuation;
import datadog.context.ContextScope;
import datadog.trace.api.civisibility.coverage.InstrumentedClass;
import datadog.trace.api.civisibility.coverage.ProbeRecorder;
import datadog.trace.api.civisibility.coverage.ProbeScopeListener;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.civisibility.coverage.probe.fixtures.ConcurrentWorkload;
import datadog.trace.civisibility.domain.TestContextImpl;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.CoreTracer;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * On a thread with a window open, the code that a scope runs under another test's context belongs
 * to that test while its window is open, and to Shared once it closed. A scope under the context of
 * the window's own test, or under no test's context, leaves the code to the window. A thread is
 * handed the owner that the code of its creator has when it is created.
 */
class ProbeScopeTest {

  /** Reference lines of each worker run alone. */
  private static final List<Map<String, BitSet>> WORKER_LINES = new ArrayList<>();

  private static AgentTracer.TracerAPI original;
  private static CoreTracer tracer;

  @BeforeAll
  static void setUp() {
    WORKER_LINES.addAll(referenceLinesOfEachWorker());
    original = AgentTracer.get();
    tracer = CoreTracer.builder().writer(new ListWriter()).build();
    AgentTracer.forceRegister(tracer);
    new ProbeScopeListener().withTracer(tracer);
  }

  @AfterAll
  static void tearDown() {
    AgentTracer.forceRegister(original);
    tracer.close();
  }

  @Test
  void codeAContinuationRunsForAFinishedTestGoesToShared() {
    FixtureLoader loader = new FixtureLoader(null, FIXTURES);
    FinishedTest registering = runTestThatCaptures(() -> run(loader, 0));

    TestWindow opening = new TestWindow();
    AgentSpan openingSpan = testSpan(opening);
    Map<String, BitSet> openingLines;
    try (ContextScope scope = tracer.activateSpan(openingSpan)) {
      try (ContextScope resumed = registering.continuation.resume()) {
        run(loader, 2);
        // a span the continuation starts carries the finished test too
        AgentSpan child = tracer.buildSpan("test", "child").start();
        try (ContextScope childScope = tracer.activateSpan(child)) {
          run(loader, 2);
        } finally {
          child.finish();
        }
        run(loader, 2);
      }
      run(loader, 1);
    } finally {
      openingSpan.finish();
      openingLines = opening.closeAndCollect();
    }

    assertEquals(WORKER_LINES.get(0), registering.lines);
    assertEquals(WORKER_LINES.get(1), openingLines);
    assertEquals(WORKER_LINES.get(2), sharedLines(loader));
  }

  @Test
  void codeAContinuationRunsForAnotherOpenTestGoesToThatTest() throws Exception {
    FixtureLoader loader = new FixtureLoader(null, FIXTURES);
    TestWindow first = new TestWindow();
    AgentSpan firstSpan = testSpan(first);
    AtomicReference<Map<String, BitSet>> secondLines = new AtomicReference<>();
    Map<String, BitSet> firstLines;
    try (ContextScope scope = tracer.activateSpan(firstSpan)) {
      run(loader, 0);
      ContextContinuation continuation = capture();
      Thread second =
          new Thread(() -> {
            TestWindow window = new TestWindow();
            AgentSpan span = testSpan(window);
            try (ContextScope secondScope = tracer.activateSpan(span)) {
              try (ContextScope resumed = continuation.resume()) {
                run(loader, 2);
              }
              run(loader, 1);
            } finally {
              span.finish();
              secondLines.set(window.closeAndCollect());
            }
          });
      second.start();
      second.join(TimeUnit.SECONDS.toMillis(30));
    } finally {
      firstSpan.finish();
      firstLines = first.closeAndCollect();
    }

    assertEquals(union(WORKER_LINES.get(0), WORKER_LINES.get(2)), firstLines);
    assertEquals(WORKER_LINES.get(1), secondLines.get());
    assertTrue(sharedLines(loader).isEmpty());
  }

  @Test
  void aScopeUnderTheContextOfTheWindowsOwnTestKeepsTheOwner() {
    FixtureLoader loader = new FixtureLoader(null, FIXTURES);
    TestWindow window = new TestWindow();
    AgentSpan span = testSpan(window);
    Map<String, BitSet> lines;
    try (ContextScope scope = tracer.activateSpan(span)) {
      run(loader, 1);
      int classId = classId(loader, ConcurrentWorkload.class);
      boolean[] probes = ProbeRecorder.probes(classId);
      AgentSpan child = tracer.buildSpan("test", "child").start();
      try (ContextScope childScope = tracer.activateSpan(child)) {
        // a new owner would hand out a new array
        assertSame(probes, ProbeRecorder.probes(classId));
        run(loader, 2);
      } finally {
        child.finish();
      }
      assertSame(probes, ProbeRecorder.probes(classId));
    } finally {
      span.finish();
      lines = window.closeAndCollect();
    }

    assertEquals(union(WORKER_LINES.get(1), WORKER_LINES.get(2)), lines);
    assertTrue(sharedLines(loader).isEmpty());
  }

  @Test
  void aScopeUnderNoTestsContextKeepsTheOwner() {
    FixtureLoader loader = new FixtureLoader(null, FIXTURES);
    TestWindow window = new TestWindow();
    Map<String, BitSet> lines;
    try {
      run(loader, 1);
      int classId = classId(loader, ConcurrentWorkload.class);
      boolean[] probes = ProbeRecorder.probes(classId);
      AgentSpan span = tracer.buildSpan("test", "other").ignoreActiveSpan().start();
      try (ContextScope scope = tracer.activateSpan(span)) {
        assertSame(probes, ProbeRecorder.probes(classId));
        run(loader, 2);
      } finally {
        span.finish();
      }
    } finally {
      lines = window.closeAndCollect();
    }

    assertEquals(union(WORKER_LINES.get(1), WORKER_LINES.get(2)), lines);
    assertTrue(sharedLines(loader).isEmpty());
  }

  @Test
  void aContinuationResumedForAnotherTestChangesTheOwner() {
    FixtureLoader loader = new FixtureLoader(null, FIXTURES);
    ContextContinuation continuation = runTestThatCaptures(() -> {}).continuation;

    TestWindow opening = new TestWindow();
    AgentSpan openingSpan = testSpan(opening);
    try (ContextScope scope = tracer.activateSpan(openingSpan)) {
      run(loader, 1);
      int classId = classId(loader, ConcurrentWorkload.class);
      boolean[] probes = ProbeRecorder.probes(classId);
      try (ContextScope resumed = continuation.resume()) {
        assertNotSame(probes, ProbeRecorder.probes(classId));
      }
    } finally {
      openingSpan.finish();
      opening.closeAndCollect();
    }
  }

  @Test
  void codeAContinuationRunsAfterEveryWindowClosedGoesToShared() {
    FixtureLoader loader = new FixtureLoader(null, FIXTURES);
    FinishedTest registering = runTestThatCaptures(() -> run(loader, 0));
    TestWindow opening = new TestWindow();
    Map<String, BitSet> openingLines;
    try {
      run(loader, 1);
    } finally {
      openingLines = opening.closeAndCollect();
    }

    try (ContextScope resumed = registering.continuation.resume()) {
      run(loader, 2);
    }

    assertEquals(WORKER_LINES.get(0), registering.lines);
    assertEquals(WORKER_LINES.get(1), openingLines);
    assertEquals(WORKER_LINES.get(2), sharedLines(loader));
  }

  @Test
  void aThreadCreatedOnAThreadWithNoWindowOpenIsHandedTheOwnerOfItsCode() throws Exception {
    FixtureLoader loader = new FixtureLoader(null, FIXTURES);
    CountDownLatch opened = new CountDownLatch(1);
    AtomicReference<AgentSpan> span = new AtomicReference<>();
    // created before the window opens, so it is handed none
    Thread creator =
        new Thread(() -> {
          await(opened);
          try (ContextScope scope = tracer.activateSpan(span.get())) {
            run(loader, 0);
          }
          // the code of the creator has no owner now, before it runs any instrumented code
          runOnNewThread(loader, 1);
          run(loader, 1);
          // and belongs to the test again, before it runs any instrumented code
          try (ContextScope scope = tracer.activateSpan(span.get())) {
            runOnNewThread(loader, 2);
          }
        });
    creator.start();
    TestWindow window = new TestWindow();
    span.set(testSpan(window));
    Map<String, BitSet> lines;
    try {
      opened.countDown();
      creator.join(TimeUnit.SECONDS.toMillis(30));
    } finally {
      span.get().finish();
      lines = window.closeAndCollect();
    }

    assertEquals(union(WORKER_LINES.get(0), WORKER_LINES.get(2)), lines);
    assertEquals(WORKER_LINES.get(1), sharedLines(loader));
  }

  /** A test that ended after it captured its context for code that runs later. */
  private static final class FinishedTest {
    final ContextContinuation continuation;
    final Map<String, BitSet> lines;

    FinishedTest(ContextContinuation continuation, Map<String, BitSet> lines) {
      this.continuation = continuation;
      this.lines = lines;
    }
  }

  /** Runs a test that runs a body and then captures its context, and ends the test. */
  private static FinishedTest runTestThatCaptures(Runnable body) {
    TestWindow window = new TestWindow();
    AgentSpan span = testSpan(window);
    ContextContinuation continuation;
    Map<String, BitSet> lines;
    try (ContextScope scope = tracer.activateSpan(span)) {
      body.run();
      continuation = capture();
    } finally {
      span.finish();
      lines = window.closeAndCollect();
    }
    return new FinishedTest(continuation, lines);
  }

  private static AgentSpan testSpan(TestWindow window) {
    return tracer
        .buildSpan("test", "test")
        .ignoreActiveSpan()
        .withRequestContextData(RequestContextSlot.CI_VISIBILITY, new TestContextImpl(window))
        .start();
  }

  /** What instrumentation captures to run code later under the active context. */
  @SuppressWarnings("deprecation")
  private static ContextContinuation capture() {
    return tracer.capture(tracer.currentContext());
  }

  private static int classId(ClassLoader loader, Class<?> fixture) {
    String name = fixture.getName().replace('.', '/');
    for (int classId = 0; classId < ProbeRecorder.getClassCount(); classId++) {
      InstrumentedClass instrumentedClass = ProbeRecorder.getInstrumentedClass(classId);
      if (instrumentedClass != null
          && instrumentedClass.loader() == loader
          && instrumentedClass.name().equals(name)) {
        return classId;
      }
    }
    throw new AssertionError(name + " is not instrumented");
  }
}

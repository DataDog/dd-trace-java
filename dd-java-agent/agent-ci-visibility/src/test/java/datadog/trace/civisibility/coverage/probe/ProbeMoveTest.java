package datadog.trace.civisibility.coverage.probe;

import static datadog.trace.civisibility.coverage.probe.ConcurrentWorkloads.FIXTURES;
import static datadog.trace.civisibility.coverage.probe.ConcurrentWorkloads.referenceLinesOfEachWorker;
import static datadog.trace.civisibility.coverage.probe.ConcurrentWorkloads.run;
import static datadog.trace.civisibility.coverage.probe.TestWindow.sharedLines;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.context.ContextScope;
import datadog.trace.api.civisibility.coverage.CoverageWindow;
import datadog.trace.api.civisibility.coverage.ProbeRecorder;
import datadog.trace.api.civisibility.coverage.ProbeScopeListener;
import datadog.trace.api.civisibility.coverage.SharedPart;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.civisibility.domain.TestContextImpl;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.CoreTracer;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * A thread moves what its probe arrays hold when its owner changes. Code that runs inside the move,
 * as a mocking library's advice in the JDK collections that the move uses does, changes no owner,
 * and the methods it starts get an array that nothing reads: their hits are dropped and counted.
 * Every other hit reaches its owner.
 */
class ProbeMoveTest {

  /** Reference lines of each worker run alone. */
  private static final List<Map<String, BitSet>> WORKER_LINES = new ArrayList<>();

  /** The methods a worker starts: run, common, and the worker's own. */
  private static final int METHODS_PER_WORKER = 3;

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
  void codeInsideAMoveIsDroppedAndTheRestReachesItsOwner() {
    FixtureLoader loader = new FixtureLoader(null, FIXTURES);
    long dropped = ProbeRecorder.getDroppedEntryCount();
    TestWindow window = new TestWindow();
    runInsideFirstMove(window, () -> run(loader, 1));
    run(loader, 0);
    Map<String, BitSet> lines = window.closeAndCollect();

    assertEquals(METHODS_PER_WORKER, ProbeRecorder.getDroppedEntryCount() - dropped);
    assertEquals(WORKER_LINES.get(0), lines);
    assertTrue(sharedLines(loader).isEmpty());
    assertTrue(sharedLines(loader, SharedPart.INIT).isEmpty());

    // the dropped methods leave nothing behind
    TestWindow next = new TestWindow();
    run(loader, 1);
    assertEquals(WORKER_LINES.get(1), next.closeAndCollect());
    assertTrue(sharedLines(loader).isEmpty());
  }

  @Test
  void codeInsideAMoveChangesNoOwner() {
    FixtureLoader loader = new FixtureLoader(null, FIXTURES);
    long dropped = ProbeRecorder.getDroppedEntryCount();
    TestWindow outer = new TestWindow();
    AgentSpan outerSpan = testSpan(new TestContextImpl(outer));
    run(loader, 0);
    TestWindow inner = new TestWindow();
    runInsideFirstMove(
        inner,
        () -> {
          try (ContextScope scope = tracer.activateSpan(outerSpan)) {
            run(loader, 2);
          }
        });
    run(loader, 1);
    Map<String, BitSet> innerLines = inner.closeAndCollect();
    outerSpan.finish();
    Map<String, BitSet> outerLines = outer.closeAndCollect();

    assertEquals(METHODS_PER_WORKER, ProbeRecorder.getDroppedEntryCount() - dropped);
    assertEquals(WORKER_LINES.get(1), innerLines);
    assertEquals(WORKER_LINES.get(0), outerLines);
    assertTrue(sharedLines(loader).isEmpty());
  }

  /**
   * Makes {@code code} run inside the first move of hits to {@code window}, as advice that a
   * mocking library puts in the JDK list the window keeps them in would.
   */
  private static void runInsideFirstMove(CoverageWindow window, Runnable code) {
    try {
      Field hits = CoverageWindow.class.getDeclaredField("hits");
      hits.setAccessible(true);
      hits.set(window, new ListRunningCodeOnFirstAdd(code));
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
  }

  private static final class ListRunningCodeOnFirstAdd extends ArrayList<CoverageWindow.ClassHits> {
    private final transient Runnable code;
    private boolean ran;

    ListRunningCodeOnFirstAdd(Runnable code) {
      this.code = code;
    }

    @Override
    public boolean addAll(Collection<? extends CoverageWindow.ClassHits> moved) {
      if (!ran) {
        ran = true;
        code.run();
      }
      return super.addAll(moved);
    }
  }

  private static AgentSpan testSpan(TestContextImpl context) {
    return tracer
        .buildSpan("test", "test")
        .ignoreActiveSpan()
        .withRequestContextData(RequestContextSlot.CI_VISIBILITY, context)
        .start();
  }
}

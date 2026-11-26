package datadog.trace.civisibility.coverage.probe;

import static datadog.trace.civisibility.coverage.probe.ConcurrentWorkloads.FIXTURES;
import static datadog.trace.civisibility.coverage.probe.ConcurrentWorkloads.await;
import static datadog.trace.civisibility.coverage.probe.ConcurrentWorkloads.join;
import static datadog.trace.civisibility.coverage.probe.ConcurrentWorkloads.referenceLinesOfEachWorker;
import static datadog.trace.civisibility.coverage.probe.ConcurrentWorkloads.run;
import static datadog.trace.civisibility.coverage.probe.TestWindow.sharedLines;
import static datadog.trace.civisibility.coverage.probe.TestWindow.union;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.context.ContextScope;
import datadog.trace.api.civisibility.coverage.CoverageStore;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.civisibility.domain.TestContextImpl;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.CoreTracer;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * On a thread with no window open, finding the owner from the context can run code that has probes,
 * as a mocking library's advice in JDK classes does. That code does not look up the owner again,
 * and its lines go to the owner the thread had before the lookup, or to Shared if it had none.
 */
class ProbeOwnerLookupTest {

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
  }

  @AfterAll
  static void tearDown() {
    AgentTracer.forceRegister(original);
    tracer.close();
  }

  @Test
  void codeTheLookupRunsGoesToTheOwnerFromBefore() {
    FixtureLoader loader = new FixtureLoader(null, FIXTURES);
    CountDownLatch opened = new CountDownLatch(1);
    AtomicReference<AgentSpan> firstSpan = new AtomicReference<>();
    AtomicReference<AgentSpan> secondSpan = new AtomicReference<>();
    // created before the windows open, so it is handed none
    Thread thread =
        new Thread(() -> {
          await(opened);
          try (ContextScope scope = tracer.activateSpan(firstSpan.get())) {
            run(loader, 0);
          }
          try (ContextScope scope = tracer.activateSpan(secondSpan.get())) {
            run(loader, 2);
          }
        });
    thread.start();
    TestWindow first = new TestWindow();
    TestWindow second = new TestWindow();
    LookupRunningCode secondContext = new LookupRunningCode(second, () -> run(loader, 1));
    firstSpan.set(testSpan(new TestContextImpl(first)));
    secondSpan.set(testSpan(secondContext));
    Map<String, BitSet> firstLines;
    Map<String, BitSet> secondLines;
    try {
      opened.countDown();
      join(thread);
    } finally {
      secondSpan.get().finish();
      firstSpan.get().finish();
      secondLines = second.closeAndCollect();
      firstLines = first.closeAndCollect();
    }

    assertEquals(1, secondContext.deepestLookup);
    assertEquals(union(WORKER_LINES.get(0), WORKER_LINES.get(1)), firstLines);
    assertEquals(WORKER_LINES.get(2), secondLines);
    assertTrue(sharedLines(loader).isEmpty());
  }

  @Test
  void codeTheLookupRunsWithNoOwnerFromBeforeGoesToShared() {
    FixtureLoader loader = new FixtureLoader(null, FIXTURES);
    CountDownLatch opened = new CountDownLatch(1);
    AtomicReference<AgentSpan> span = new AtomicReference<>();
    // created before the window opens, so it is handed none
    Thread thread =
        new Thread(() -> {
          await(opened);
          try (ContextScope scope = tracer.activateSpan(span.get())) {
            run(loader, 2);
          }
        });
    thread.start();
    TestWindow window = new TestWindow();
    LookupRunningCode context = new LookupRunningCode(window, () -> run(loader, 1));
    span.set(testSpan(context));
    Map<String, BitSet> lines;
    try {
      opened.countDown();
      join(thread);
    } finally {
      span.get().finish();
      lines = window.closeAndCollect();
    }

    assertEquals(1, context.deepestLookup);
    assertEquals(WORKER_LINES.get(2), lines);
    assertEquals(WORKER_LINES.get(1), sharedLines(loader));
  }

  /**
   * The context of a test whose coverage store, the first time it is looked up, runs code that has
   * probes. It counts how deep lookups nest.
   */
  private static final class LookupRunningCode extends TestContextImpl {
    private final Runnable code;
    private boolean ran;
    private int depth;
    int deepestLookup;

    LookupRunningCode(CoverageStore coverageStore, Runnable code) {
      super(coverageStore);
      this.code = code;
    }

    @Override
    public CoverageStore getCoverageStore() {
      depth++;
      try {
        deepestLookup = Math.max(deepestLookup, depth);
        if (!ran) {
          ran = true;
          code.run();
        }
        return super.getCoverageStore();
      } finally {
        depth--;
      }
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

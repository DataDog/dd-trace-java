package datadog.trace.civisibility.coverage.probe;

import static datadog.trace.api.TracePropagationStyle.DATADOG;
import static datadog.trace.api.sampling.PrioritySampling.UNSET;
import static datadog.trace.civisibility.coverage.probe.ConcurrentWorkloads.FIXTURES;
import static datadog.trace.civisibility.coverage.probe.ConcurrentWorkloads.await;
import static datadog.trace.civisibility.coverage.probe.ConcurrentWorkloads.join;
import static datadog.trace.civisibility.coverage.probe.ConcurrentWorkloads.referenceLinesOfEachWorker;
import static datadog.trace.civisibility.coverage.probe.ConcurrentWorkloads.run;
import static datadog.trace.civisibility.coverage.probe.TestWindow.sharedLines;
import static datadog.trace.civisibility.coverage.probe.TestWindow.union;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.context.ContextScope;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.civisibility.InstrumentationTestBridge;
import datadog.trace.api.civisibility.coverage.ProbeScopeListener;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.civisibility.domain.TestContextImpl;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.propagation.ExtractedContext;
import datadog.trace.core.propagation.PropagationTags;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * The code that runs under a span of a test's trace whose local root is not the test's span, such
 * as the span of an in-process server that the test calls through an instrumented client, belongs
 * to the test while its window is open, and to Shared once it closed.
 */
@ExtendWith(NoDroppedEntries.class)
class ProbeTraceTest {

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
  void aServerSpanIsInTheTestsTraceButCarriesNoTest() {
    TestWindow window = new TestWindow();
    AgentSpan test = testSpan(window);
    AgentSpan server = tracer.buildSpan("test", "server").asChildOf(remoteParent(test)).start();
    try {
      assertEquals(test.getTraceId(), server.getTraceId());
      assertNotNull(InstrumentationTestBridge.getTestContext(test));
      assertNull(InstrumentationTestBridge.getTestContext(server));
    } finally {
      server.finish();
      test.finish();
      window.closeAndCollect();
    }
  }

  @Test
  void codeAServerRunsOnItsThreadForATestGoesToTheTest() {
    FixtureLoader loader = new FixtureLoader(null, FIXTURES);
    Map<String, BitSet> lines;
    try (Server server = new Server()) {
      TestWindow window = new TestWindow();
      AgentSpan test = testSpan(window);
      try (ContextScope scope = tracer.activateSpan(test)) {
        run(loader, 0);
        server.call(() -> run(loader, 1));
      } finally {
        test.finish();
        lines = window.closeAndCollect();
      }
    }

    assertEquals(union(WORKER_LINES.get(0), WORKER_LINES.get(1)), lines);
    assertTrue(sharedLines(loader).isEmpty());
  }

  @Test
  void codeAServerRunsForATestThatEndedGoesToShared() {
    FixtureLoader loader = new FixtureLoader(null, FIXTURES);
    Map<String, BitSet> lines;
    try (Server server = new Server()) {
      TestWindow window = new TestWindow();
      AgentSpan test = testSpan(window);
      try (ContextScope scope = tracer.activateSpan(test)) {
        run(loader, 0);
      } finally {
        test.finish();
        lines = window.closeAndCollect();
      }
      server.serve(remoteParent(test), () -> run(loader, 2));
    }

    assertEquals(WORKER_LINES.get(0), lines);
    assertEquals(WORKER_LINES.get(2), sharedLines(loader));
  }

  @Test
  void codeAServerRunsForAnotherTraceGoesToShared() {
    FixtureLoader loader = new FixtureLoader(null, FIXTURES);
    Map<String, BitSet> lines;
    try (Server server = new Server()) {
      TestWindow window = new TestWindow();
      AgentSpan test = testSpan(window);
      try (ContextScope scope = tracer.activateSpan(test)) {
        run(loader, 0);
        server.serve(remoteParent(DDTraceId.from(42), 43), () -> run(loader, 2));
      } finally {
        test.finish();
        lines = window.closeAndCollect();
      }
    }

    assertEquals(WORKER_LINES.get(0), lines);
    assertEquals(WORKER_LINES.get(2), sharedLines(loader));
  }

  @Test
  void requestsOfTwoOpenTestsOnOneServerThreadGoToEachTest() {
    FixtureLoader loader = new FixtureLoader(null, FIXTURES);
    AtomicReference<Map<String, BitSet>> firstLines = new AtomicReference<>();
    AtomicReference<Map<String, BitSet>> secondLines = new AtomicReference<>();
    try (Server server = new Server()) {
      CyclicBarrier bothOpen = new CyclicBarrier(2);
      Thread first =
          new Thread(() ->
              runTest(
                  bothOpen,
                  firstLines,
                  () -> {
                    run(loader, 0);
                    server.call(() -> run(loader, 1));
                  }));
      Thread second =
          new Thread(() -> runTest(bothOpen, secondLines, () -> server.call(() -> run(loader, 2))));
      first.start();
      second.start();
      join(first);
      join(second);
    }

    assertEquals(union(WORKER_LINES.get(0), WORKER_LINES.get(1)), firstLines.get());
    assertEquals(WORKER_LINES.get(2), secondLines.get());
    assertTrue(sharedLines(loader).isEmpty());
  }

  @Test
  void codeAServerRunsOnTheTestsThreadForTheTestStaysWithTheTest() {
    FixtureLoader loader = new FixtureLoader(null, FIXTURES);
    TestWindow window = new TestWindow();
    AgentSpan test = testSpan(window);
    Map<String, BitSet> lines;
    try (ContextScope scope = tracer.activateSpan(test)) {
      run(loader, 0);
      // a server that runs its handler on the calling thread
      runUnderServerSpan(remoteParent(test), () -> run(loader, 1));
      run(loader, 2);
    } finally {
      test.finish();
      lines = window.closeAndCollect();
    }

    assertEquals(union(WORKER_LINES.get(0), WORKER_LINES.get(1), WORKER_LINES.get(2)), lines);
    assertTrue(sharedLines(loader).isEmpty());
  }

  /**
   * Runs a test on the current thread: its body runs once every test that waits on {@code bothOpen}
   * is open, and the test ends once all their bodies returned.
   */
  private static void runTest(
      CyclicBarrier bothOpen, AtomicReference<Map<String, BitSet>> lines, Runnable body) {
    TestWindow window = new TestWindow();
    AgentSpan test = testSpan(window);
    try (ContextScope scope = tracer.activateSpan(test)) {
      await(bothOpen);
      body.run();
      await(bothOpen);
    } finally {
      test.finish();
      lines.set(window.closeAndCollect());
    }
  }

  /**
   * An in-process server with one thread, which runs each request's handler under a server span
   * whose remote parent is the caller's span. Its thread is created with no window open, so it is
   * handed none.
   */
  private static final class Server implements AutoCloseable {
    private static final Runnable STOP = () -> {};

    private final BlockingQueue<Runnable> requests = new LinkedBlockingQueue<>();
    private final Thread thread = new Thread(this::serveRequests, "server");

    Server() {
      thread.start();
    }

    /** Calls the server through a client span, as an instrumented client does. */
    void call(Runnable handler) {
      AgentSpan client = tracer.buildSpan("test", "client").start();
      try {
        serve(remoteParent(client), handler);
      } finally {
        client.finish();
      }
    }

    /** Runs a request on the server's thread, and waits for its handler to return. */
    void serve(AgentSpanContext parent, Runnable handler) {
      FutureTask<Void> request = new FutureTask<>(() -> runUnderServerSpan(parent, handler), null);
      requests.add(request);
      try {
        request.get(30, TimeUnit.SECONDS);
      } catch (Exception e) {
        throw new AssertionError(e);
      }
    }

    private void serveRequests() {
      try {
        for (Runnable request = requests.take(); request != STOP; request = requests.take()) {
          request.run();
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    @Override
    public void close() {
      requests.add(STOP);
      join(thread);
    }
  }

  private static void runUnderServerSpan(AgentSpanContext parent, Runnable handler) {
    AgentSpan server = tracer.buildSpan("test", "server").asChildOf(parent).start();
    try (ContextScope scope = tracer.activateSpan(server)) {
      handler.run();
    } finally {
      server.finish();
    }
  }

  /** The context a server extracts from a request that {@code caller} sends. */
  private static AgentSpanContext remoteParent(AgentSpan caller) {
    return remoteParent(caller.getTraceId(), caller.getSpanId());
  }

  private static AgentSpanContext remoteParent(DDTraceId traceId, long spanId) {
    return new ExtractedContext(
        traceId, spanId, UNSET, null, PropagationTags.factory().empty(), DATADOG);
  }

  private static AgentSpan testSpan(TestWindow window) {
    AgentSpan span =
        tracer
            .buildSpan("test", "test")
            .ignoreActiveSpan()
            .withRequestContextData(RequestContextSlot.CI_VISIBILITY, new TestContextImpl(window))
            .start();
    window.setTestTraceId(span.getTraceId());
    return span;
  }
}

package datadog.trace.instrumentation.guidewire;

import static datadog.trace.agent.test.assertions.SpanMatcher.span;
import static datadog.trace.agent.test.assertions.TraceMatcher.SORT_BY_START_TIME;
import static datadog.trace.agent.test.assertions.TraceMatcher.trace;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;

import datadog.context.ContextScope;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import gw.internal.xml.ws.AsyncResponseImpl;
import gw.internal.xml.ws.UnrelatedWorker;
import org.junit.jupiter.api.Test;

class WsiAsyncResponseInstrumentationTest extends AbstractInstrumentationTest {

  @FunctionalInterface
  interface Body {
    void run() throws Exception;
  }

  private static void runUnderTrace(String operationName, Body body) throws Exception {
    AgentSpan span = startSpan("guidewire-test", operationName);
    try (ContextScope scope = activateSpan(span)) {
      body.run();
    } finally {
      span.finish();
    }
  }

  @Test
  void namedWorkerPropagatesContext() throws Exception {
    // Constructed and run while the caller's span is active; invoke() blocks until the worker ends.
    runUnderTrace("parent", () -> new AsyncResponseImpl().invoke());

    assertTraces(
        trace(
            SORT_BY_START_TIME,
            span().root().operationName("parent"),
            span().childOfPrevious().operationName("soap.call")));
  }

  @Test
  void anonymousWorkerPropagatesContext() throws Exception {
    runUnderTrace("parent", () -> AsyncResponseImpl.anonymous().invoke());

    assertTraces(
        trace(
            SORT_BY_START_TIME,
            span().root().operationName("parent"),
            span().childOfPrevious().operationName("soap.call")));
  }

  @Test
  void synchronousRunPropagatesContext() throws Exception {
    // callTimeout <= 0 path: AsyncResponseImpl.run() calls _thread.run() on the caller thread.
    runUnderTrace("parent", () -> new AsyncResponseImpl().invokeSync());

    assertTraces(
        trace(
            SORT_BY_START_TIME,
            span().root().operationName("parent"),
            span().childOfPrevious().operationName("soap.call")));
  }

  @Test
  void unrelatedThreadIsNotInstrumented() throws Exception {
    // Same construction pattern, but a class the narrow matcher must ignore.
    runUnderTrace(
        "parent",
        () -> {
          UnrelatedWorker worker = new UnrelatedWorker();
          worker.start();
          worker.join();
        });

    // No propagation: the worker's span starts its own trace instead of joining "parent".
    assertTraces(
        trace(span().root().operationName("parent")),
        trace(span().root().operationName("unrelated.work")));
  }

  @Test
  void noContextLeakToSubsequentInvocation() throws Exception {
    runUnderTrace("parent", () -> new AsyncResponseImpl().invoke());
    // Second invocation runs with no active span: capture is a no-op, so soap.call is its own root.
    new AsyncResponseImpl().invoke();

    assertTraces(
        trace(
            SORT_BY_START_TIME,
            span().root().operationName("parent"),
            span().childOfPrevious().operationName("soap.call")),
        trace(span().root().operationName("soap.call")));
  }

  @Test
  void workerConstructedButNeverRunDoesNotCorruptLaterTraces() throws Exception {
    // Constructed under an active span but never run: capture happens but is never activated.
    // Guards that the stranded continuation does not mis-attribute a later, unrelated trace.
    runUnderTrace("outer", () -> new AsyncResponseImpl());
    runUnderTrace("independent", () -> {});

    // 'independent' is a clean, standalone root regardless of the stranded continuation.
    assertTraces(trace(span().root().operationName("independent")));
  }
}

package datadog.trace.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.context.TraceScope;
import java.util.concurrent.ConcurrentLinkedDeque;
import org.junit.jupiter.api.Test;

public class PendingTraceStrictWriteTest extends PendingTraceTestBase {

  @Test
  void traceIsNotReportedUntilUnfinishedContinuationIsClosed() throws Exception {
    TraceScope scope = tracer.activateSpan(rootSpan);
    AgentScope.Continuation continuation = tracer.captureActiveSpan();
    scope.close();
    rootSpan.finish();

    assertEquals(1, traceCollector.getPendingReferenceCount());
    assertEquals(1, traceCollector.getSpans().size());
    assertEquals(rootSpan, ((ConcurrentLinkedDeque<DDSpan>) traceCollector.getSpans()).peek());
    assertEquals(0, writer.size());

    // root span buffer delay expires
    writer.waitForTracesMax(1, 1);

    assertEquals(1, traceCollector.getPendingReferenceCount());
    assertEquals(1, traceCollector.getSpans().size());
    assertEquals(rootSpan, ((ConcurrentLinkedDeque<DDSpan>) traceCollector.getSpans()).peek());
    assertEquals(0, writer.size());
    assertEquals(0, writer.traceCount.get());

    // continuation is closed
    continuation.cancel();

    assertEquals(0, traceCollector.getPendingReferenceCount());
    assertTrue(traceCollector.getSpans().isEmpty());
    assertEquals(1, writer.size());
    assertEquals(1, writer.get(0).size());
    assertEquals(rootSpan, writer.get(0).get(0));
    assertEquals(1, writer.traceCount.get());
  }

  @Test
  void negativeReferenceCountThrowsAnException() throws Exception {
    TraceScope scope = tracer.activateSpan(rootSpan);
    AgentScope.Continuation continuation = tracer.captureActiveSpan();
    scope.close();
    rootSpan.finish();

    assertEquals(1, traceCollector.getPendingReferenceCount());
    assertEquals(1, traceCollector.getSpans().size());
    assertEquals(rootSpan, ((ConcurrentLinkedDeque<DDSpan>) traceCollector.getSpans()).peek());
    assertEquals(0, writer.size());

    // continuation is finished the first time
    continuation.cancel();

    assertEquals(0, traceCollector.getPendingReferenceCount());
    assertTrue(traceCollector.getSpans().isEmpty());
    assertEquals(1, writer.size());
    assertEquals(1, writer.get(0).size());
    assertEquals(rootSpan, writer.get(0).get(0));
    assertEquals(1, writer.traceCount.get());

    // continuation is finished the second time
    // Yes this should be guarded by the used flag in the continuation,
    // so remove it anyway to trigger the exception
    assertThrows(
        IllegalStateException.class, () -> traceCollector.removeContinuation(continuation));
  }
}

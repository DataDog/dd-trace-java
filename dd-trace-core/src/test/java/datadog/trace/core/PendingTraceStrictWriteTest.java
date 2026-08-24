package datadog.trace.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.context.ContextContinuation;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import java.util.ArrayList;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

public class PendingTraceStrictWriteTest extends PendingTraceTestBase {

  @Test
  void traceNotReportedUntilContinuationClosed() throws InterruptedException {
    AgentScope scope = tracer.activateSpan(rootSpan);
    ContextContinuation continuation = tracer.captureActiveSpan();
    scope.close();
    rootSpan.finish();

    assertEquals(1, traceCollector.getPendingReferenceCount());
    assertEquals(Arrays.asList(rootSpan), new ArrayList<>(traceCollector.getSpans()));
    assertTrue(writer.isEmpty());
    // root span buffer delay expires
    writer.waitForTracesMax(1, 1);

    assertEquals(1, traceCollector.getPendingReferenceCount());
    assertEquals(Arrays.asList(rootSpan), new ArrayList<>(traceCollector.getSpans()));
    assertTrue(writer.isEmpty());
    assertEquals(0, writer.getTraceCount());
    // continuation is closed
    continuation.release();

    assertEquals(0, traceCollector.getPendingReferenceCount());
    assertTrue(traceCollector.getSpans().isEmpty());
    assertEquals(Arrays.asList(Arrays.asList(rootSpan)), new ArrayList<>(writer));
    assertEquals(1, writer.getTraceCount());
  }

  @Test
  void negativeReferenceCountThrowsException() {
    AgentScope scope = tracer.activateSpan(rootSpan);
    ContextContinuation continuation = tracer.captureActiveSpan();
    scope.close();
    rootSpan.finish();

    assertEquals(1, traceCollector.getPendingReferenceCount());
    assertEquals(Arrays.asList(rootSpan), new ArrayList<>(traceCollector.getSpans()));
    assertTrue(writer.isEmpty());
    // continuation is finished the first time
    continuation.release();

    assertEquals(0, traceCollector.getPendingReferenceCount());
    assertTrue(traceCollector.getSpans().isEmpty());
    assertEquals(Arrays.asList(Arrays.asList(rootSpan)), new ArrayList<>(writer));
    assertEquals(1, writer.getTraceCount());
    // continuation is finished the second time
    // Yes this should be guarded by the used flag in the continuation,
    // so remove it anyway to trigger the exception
    assertThrows(
        IllegalStateException.class, () -> traceCollector.removeContinuation(continuation));
  }
}

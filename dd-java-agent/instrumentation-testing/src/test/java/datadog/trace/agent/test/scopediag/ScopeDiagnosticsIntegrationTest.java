package datadog.trace.agent.test.scopediag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.CoreTracer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Exercises the full path: a real {@link CoreTracer} capturing continuations via {@code
 * captureSpan} drives {@code ScopeContinuation} -> {@code ScopeContinuationProbe} -> {@link
 * ScopeDiagnostics}, and the derived report classifies the leak correctly.
 */
class ScopeDiagnosticsIntegrationTest {

  private CoreTracer tracer;

  @AfterEach
  void tearDown() {
    ScopeDiagnostics.stop();
    ScopeDiagnostics.reset();
    if (tracer != null) {
      tracer.close();
    }
  }

  @Test
  void capturesRealLeakAndResolvedContinuation() {
    tracer = CoreTracer.builder().writer(new ListWriter()).strictTraceWrites(false).build();

    ScopeDiagnostics.startRecording();

    AgentSpan span = tracer.startSpan("test", "op");
    AgentScope.Continuation leaked = tracer.captureSpan(span); // captured, never resolved
    AgentScope.Continuation resolved = tracer.captureSpan(span);
    resolved.cancel();

    ScopeDiagnosticsReport report = ScopeDiagnostics.report();

    assertEquals(2, report.records().size(), "both captures recorded");
    assertEquals(1, report.leakCount(), "exactly the un-resolved continuation leaks");
    assertTrue(report.hasProblems());
    // keep a reference so the leak isn't reclaimed before the assertion
    assertFalse(leaked.toString().isEmpty());

    span.finish();
  }

  @Test
  void resolvedContinuationDoesNotLeak() {
    tracer = CoreTracer.builder().writer(new ListWriter()).strictTraceWrites(false).build();

    ScopeDiagnostics.startRecording();

    AgentSpan span = tracer.startSpan("test", "op");
    AgentScope.Continuation continuation = tracer.captureSpan(span);
    AgentScope scope = continuation.activate();
    scope.close();
    span.finish();

    ScopeDiagnosticsReport report = ScopeDiagnostics.report();

    assertEquals(1, report.records().size());
    assertEquals(0, report.leakCount(), "activated then closed continuation is resolved");
  }
}

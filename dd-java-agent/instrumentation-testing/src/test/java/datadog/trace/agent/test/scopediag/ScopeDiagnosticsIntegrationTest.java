package datadog.trace.agent.test.scopediag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.CoreTracer;
import java.util.List;
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
  void sameSpanReactivationIsNotFlaggedActivateAfterResolve() {
    tracer = CoreTracer.builder().writer(new ListWriter()).strictTraceWrites(false).build();

    ScopeDiagnostics.startRecording();

    AgentSpan span = tracer.startSpan("test", "op");
    AgentScope active = tracer.activateSpan(span); // span becomes the active top scope
    // Capturing then immediately activating the already-active span hits the continueSpan reuse
    // optimization: it cancels the continuation from inside activate() before activate() returns.
    // The resume must be timestamped at activate() entry (not exit) so it does not appear to occur
    // after that internal resolution and spuriously trip ACTIVATE_AFTER_RESOLVE.
    AgentScope.Continuation continuation = tracer.captureSpan(span);
    AgentScope reused = continuation.activate();
    reused.close();
    active.close();
    span.finish();

    ScopeDiagnosticsReport report = ScopeDiagnostics.report();

    assertEquals(1, report.records().size());
    assertEquals(
        0,
        report.activateAfterResolveCount(),
        "a same-span re-activation resolved during activate() is not activate-after-resolve");
    assertEquals(0, report.leakCount());
    assertFalse(report.hasProblems());
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

  @Test
  void scopeLifetimeRecordedAndLinkedToContinuation() {
    tracer = CoreTracer.builder().writer(new ListWriter()).strictTraceWrites(false).build();

    ScopeDiagnostics.startRecording();

    AgentSpan span = tracer.startSpan("test", "op");
    AgentScope.Continuation continuation = tracer.captureSpan(span);
    AgentScope scope = continuation.activate();
    scope.close();
    span.finish();

    ScopeDiagnosticsReport report = ScopeDiagnostics.report();

    ScopeRecord linked = continuationScope(report);
    assertNotNull(linked, "the resumed scope was recorded");
    assertNotNull(linked.open(), "scope open observed");
    assertTrue(linked.closed(), "scope close observed");
    assertEquals(0, report.neverClosedScopeCount());
    // the scope links back to its continuation record
    assertEquals(1, report.records().size());
    assertEquals(Long.valueOf(report.records().get(0).seq), linked.continuationSeq);
  }

  @Test
  void neverClosedScopeIsFlagged() {
    tracer = CoreTracer.builder().writer(new ListWriter()).strictTraceWrites(false).build();

    ScopeDiagnostics.startRecording();

    AgentSpan span = tracer.startSpan("test", "op");
    AgentScope.Continuation continuation = tracer.captureSpan(span);
    AgentScope scope = continuation.activate(); // opened, never closed

    ScopeDiagnosticsReport report = ScopeDiagnostics.report();

    assertEquals(1, report.neverClosedScopeCount(), "the open scope never closed");
    assertEquals(1, report.leakCount(), "and the continuation it backs also leaks");
    assertTrue(report.hasProblems());

    // clean up so the open scope does not pollute this thread's scope stack for later tests
    scope.close();
    span.finish();
  }

  private static ScopeRecord continuationScope(ScopeDiagnosticsReport report) {
    List<ScopeRecord> scopes = report.scopeRecords();
    for (ScopeRecord s : scopes) {
      if (s.continuationSeq != null) {
        return s;
      }
    }
    return null;
  }
}

package datadog.trace.agent.test.scopediag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.context.Context;
import datadog.context.ContextContinuation;
import datadog.trace.api.DDTraceId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ScopeDiagnosticsAdmissionTest {
  @AfterEach
  void reset() {
    ScopeDiagnostics.reset();
  }

  @Test
  void failedActivationBeforeResolutionAdviceIsRetained() {
    ScopeDiagnostics.startRecording();
    Object window = ScopeDiagnostics.recordingWindow();
    ContextContinuation continuation = Context.root().capture();
    ScopeDiagnostics.recordCapture(window, continuation, DDTraceId.from(1), 2, "op", (byte) 0);

    // The tracer has resolved, but its exit advice has not reached the recorder yet.
    ScopeDiagnostics.recordActivateFailed(window, continuation);
    assertEquals(1, ScopeDiagnostics.report().records().get(0).failedActivations().size());
    assertEquals(0, ScopeDiagnostics.report().activateAfterResolveCount());
    ScopeDiagnostics.recordResolve(window, continuation, true, System.nanoTime(), false);

    ScopeDiagnosticsReport report = ScopeDiagnostics.report();
    assertEquals(0, report.leakCount());
    assertEquals(1, report.activateAfterResolveCount());
    assertTrue(report.hasProblems());
  }

  @Test
  void failedActivationWithoutAnObservedContinuationIsIgnored() {
    ScopeDiagnostics.startRecording();
    ScopeDiagnostics.recordActivateFailed(
        ScopeDiagnostics.recordingWindow(), Context.root().capture());
    assertTrue(ScopeDiagnostics.report().records().isEmpty());
  }

  @Test
  void delayedEventsCannotEnterTheNextRecordingWindow() {
    ScopeDiagnostics.startRecording();
    Object previousWindow = ScopeDiagnostics.recordingWindow();
    ScopeDiagnostics.stop();
    ScopeDiagnostics.reset();
    ScopeDiagnostics.startRecording();
    Object currentWindow = ScopeDiagnostics.recordingWindow();
    ContextContinuation continuation = Context.root().capture();
    Object scope = new Object();
    DDTraceId trace = DDTraceId.from(1);

    // Deliver callbacks that read their window before stop but arrive after the next start.
    ScopeDiagnostics.recordCapture(previousWindow, continuation, trace, 2, "old", (byte) 0);
    ScopeDiagnostics.recordActivate(previousWindow, continuation, trace, 2, "old", (byte) 0, 1);
    ScopeDiagnostics.recordActivateFailed(previousWindow, continuation);
    ScopeDiagnostics.recordResolve(previousWindow, continuation, true, 2, false);
    ScopeDiagnostics.recordRootWritten(previousWindow, trace);
    ScopeDiagnostics.recordScopeOpen(
        previousWindow, scope, trace, 2, "old", (byte) 0, continuation);
    ScopeDiagnostics.recordScopeClose(previousWindow, scope);
    ScopeDiagnostics.recordScopeCloseWrongThread(previousWindow, scope);
    ScopeDiagnostics.recordDeferredScopeCleanup(previousWindow, scope);

    assertTrue(ScopeDiagnostics.report().records().isEmpty());
    assertTrue(ScopeDiagnostics.report().scopeRecords().isEmpty());

    // Old resolution/deferred-cleanup/write events must not poison fresh records either.
    ScopeDiagnostics.recordCapture(currentWindow, continuation, trace, 2, "new", (byte) 0);
    ScopeDiagnostics.recordScopeOpen(currentWindow, scope, trace, 2, "new", (byte) 0, continuation);
    ScopeDiagnostics.recordResolve(currentWindow, continuation, true, Long.MAX_VALUE, false);
    assertEquals(0, ScopeDiagnostics.report().deferredCleanupScopeCount());
    ScopeDiagnostics.recordScopeClose(currentWindow, scope);
    ScopeDiagnosticsReport report = ScopeDiagnostics.report();
    assertEquals(1, report.records().size());
    assertEquals(0, report.leakCount());
    assertEquals(0, report.lateCount());
    assertFalse(report.hasProblems());
  }
}

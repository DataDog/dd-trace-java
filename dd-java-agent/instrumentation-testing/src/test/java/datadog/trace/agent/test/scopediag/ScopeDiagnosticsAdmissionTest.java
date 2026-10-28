package datadog.trace.agent.test.scopediag;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.context.Context;
import datadog.context.ContextContinuation;
import datadog.trace.api.DDTraceId;
import datadog.trace.bootstrap.instrumentation.api.NoopScope;
import datadog.trace.test.util.PollingConditions;
import java.util.concurrent.FutureTask;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class ScopeDiagnosticsAdmissionTest {
  @AfterEach
  void reset() {
    ScopeDiagnostics.reset();
  }

  @Test
  void callbacksDoNotWaitForTheReportLock() throws Exception {
    ScopeDiagnostics.startRecording();
    Object window = ScopeDiagnostics.recordingWindow();
    ContextContinuation continuation = Context.root().capture();
    Object scope = new Object();
    DDTraceId trace = DDTraceId.from(1);
    FutureTask<Void> callbacks =
        new FutureTask<>(
            () -> {
              ScopeDiagnostics.recordCapture(window, continuation, trace, 2, "op", (byte) 0);
              ScopeDiagnostics.recordActivate(
                  window, continuation, trace, 2, "op", (byte) 0, System.nanoTime());
              ScopeDiagnostics.recordScopeOpen(
                  window, scope, trace, 2, "op", (byte) 0, continuation);
              ScopeDiagnostics.recordDeferredScopeCleanup(window, scope);
              ScopeDiagnostics.recordScopeCloseWrongThread(window, scope);
              ScopeDiagnostics.recordScopeClose(window, scope);
              ScopeDiagnostics.recordResolve(window, continuation, true, System.nanoTime(), false);
              ScopeDiagnostics.recordRootWritten(window, trace);
              ScopeDiagnostics.recordActivateFailed(window, continuation);
              return null;
            });
    Thread carrier = new Thread(callbacks, "diagnostic-carrier");
    carrier.setDaemon(true);
    ScopeDiagnostics.INSTANCE.lifecycleLock.lock();
    try {
      carrier.start();
      // A carrier must return even when the diagnostic monitor's owner cannot run yet.
      callbacks.get(5, SECONDS);
    } finally {
      ScopeDiagnostics.INSTANCE.lifecycleLock.unlock();
    }
    ScopeDiagnostics.stop();

    ScopeDiagnosticsReport report = ScopeDiagnostics.report();
    assertEquals(1, report.records().size());
    ContinuationRecord record = report.records().get(0);
    assertEquals("diagnostic-carrier", record.capture().threadName);
    assertEquals(1, record.resumes().size());
    assertEquals("diagnostic-carrier", record.resumes().get(0).threadName);
    assertEquals(ContinuationStatus.RELEASED, record.status());
    assertEquals(1, record.failedActivations().size());
    assertEquals(1, report.scopeRecords().size());
    ScopeRecord scopeRecord = report.scopeRecords().get(0);
    assertTrue(scopeRecord.closed());
    assertEquals("diagnostic-carrier", scopeRecord.open().threadName);
    assertEquals("diagnostic-carrier", scopeRecord.close().threadName);
    assertEquals(1, scopeRecord.wrongThreadCloses().size());
    assertEquals(0, report.lateCount());
    assertEquals(0, report.leakCount());
  }

  @Test
  void queuedResolutionWakesQuiescenceWaiter() throws Exception {
    ScopeDiagnostics.startRecording();
    Object window = ScopeDiagnostics.recordingWindow();
    ContextContinuation continuation = Context.root().capture();
    ScopeDiagnostics.recordCapture(window, continuation, DDTraceId.from(1), 2, "op", (byte) 0);
    FutureTask<Void> waiting =
        new FutureTask<>(
            () -> {
              ScopeDiagnostics.awaitQuiescence(30_000);
              return null;
            });
    Thread waiter = new Thread(waiting, "diagnostic-quiescence");
    waiter.setDaemon(true);
    waiter.start();
    try {
      Object diagnostics = ScopeDiagnostics.INSTANCE;
      new PollingConditions(5)
          .eventually(() -> assertEquals(diagnostics, LockSupport.getBlocker(waiter)));
      ScopeDiagnostics.recordResolve(window, continuation, true, System.nanoTime(), false);
      waiting.get(5, SECONDS);
      assertFalse(ScopeDiagnostics.report().hasIncompleteLifecycles());
    } finally {
      waiter.interrupt();
      waiter.join(5000);
    }
  }

  @Test
  void quiescentStopWaitsForAdmittedScopeCleanup() throws Exception {
    ScopeDiagnostics.startRecording();
    Object window = ScopeDiagnostics.recordingWindow();
    Object scope = new Object();
    ScopeDiagnostics.recordScopeOpen(window, scope, DDTraceId.from(1), 2, "op", (byte) 0, null);
    FutureTask<Void> stopping =
        new FutureTask<>(
            () -> {
              ScopeDiagnostics.stopAfterQuiescence(30_000);
              return null;
            });
    Thread stopper = new Thread(stopping, "diagnostic-quiescent-stop");
    stopper.setDaemon(true);
    stopper.start();
    try {
      new PollingConditions(5)
          .eventually(
              () -> assertEquals(ScopeDiagnostics.INSTANCE, LockSupport.getBlocker(stopper)));
      assertFalse(stopping.isDone());
      ScopeDiagnostics.recordScopeClose(window, scope);
      stopping.get(5, SECONDS);
      assertNull(ScopeDiagnostics.recordingWindow());
      ScopeDiagnosticsReport report = ScopeDiagnostics.report();
      assertEquals(1, report.scopeRecords().size());
      assertTrue(report.scopeRecords().get(0).closed());
      assertFalse(report.hasViolations());
    } finally {
      stopper.interrupt();
      stopper.join(5000);
    }
  }

  @Test
  void quiescentStopRejectsLaterScopes() {
    ScopeDiagnostics.startRecording();
    Object window = ScopeDiagnostics.recordingWindow();
    ScopeDiagnostics.stopAfterQuiescence(30_000);
    assertNull(ScopeDiagnostics.recordingWindow());
    ScopeDiagnostics.recordScopeOpen(
        window, new Object(), DDTraceId.from(1), 2, "late", (byte) 0, null);
    assertTrue(ScopeDiagnostics.report().scopeRecords().isEmpty());
  }

  @Test
  void quiescentStopStillReportsUnclosedScopesAtDeadline() {
    ScopeDiagnostics.startRecording();
    ScopeDiagnostics.recordScopeOpen(
        ScopeDiagnostics.recordingWindow(),
        new Object(),
        DDTraceId.from(1),
        2,
        "unclosed",
        (byte) 0,
        null);
    ScopeDiagnostics.stopAfterQuiescence(0);
    assertNull(ScopeDiagnostics.recordingWindow());
    assertEquals(1, ScopeDiagnostics.report().neverClosedScopeCount());
    assertTrue(ScopeDiagnostics.report().hasViolations());
  }

  @Test
  void resetDiscardsQueuedEvents() {
    ScopeDiagnostics.startRecording();
    ScopeDiagnostics.recordCapture(
        ScopeDiagnostics.recordingWindow(),
        Context.root().capture(),
        DDTraceId.from(1),
        2,
        "old",
        (byte) 0);
    ScopeDiagnostics.reset();
    ScopeDiagnostics.startRecording();
    assertTrue(ScopeDiagnostics.report().records().isEmpty());
  }

  @Test
  void stopRetainsReservationsWhoseStackCaptureIsStillInFlight() throws Exception {
    ScopeDiagnostics.startRecording();
    Object window = ScopeDiagnostics.recordingWindow();
    ContextContinuation continuation = Context.root().capture();
    ScopeDiagnostics.recordCapture(window, continuation, DDTraceId.from(1), 2, "op", (byte) 0);
    DiagnosticEventBuffer.Event reservation = ((DiagnosticEventBuffer) window).reserve();
    ScopeDiagnostics.recordResolve(window, continuation, true, System.nanoTime(), false);
    FutureTask<Void> stopping =
        new FutureTask<>(
            () -> {
              ScopeDiagnostics.stop();
              return null;
            });
    Thread stopper = new Thread(stopping, "diagnostic-stop");
    stopper.setDaemon(true);
    stopper.start();
    try {
      new PollingConditions(5).eventually(() -> assertNull(ScopeDiagnostics.recordingWindow()));
      // Publish after stop closes admission; the reservation still belongs to this recording.
      reservation.publish(() -> {});
      stopping.get(5, SECONDS);
      assertEquals(0, ScopeDiagnostics.report().leakCount());
    } finally {
      reservation.publishIfAbsent();
      stopper.join(5000);
    }
  }

  @Test
  void callbacksConsumeEventsWithoutWaitingForAReport() {
    ScopeDiagnostics.startRecording();
    DiagnosticEventBuffer window = (DiagnosticEventBuffer) ScopeDiagnostics.recordingWindow();
    ScopeDiagnostics.recordCapture(
        window, Context.root().capture(), DDTraceId.from(1), 2, "op", (byte) 0);
    assertFalse(window.hasPending());
  }

  @Test
  void firstResolutionAndDuplicateReleaseArePreservedBehindDelayedAdmission() {
    ScopeDiagnostics.startRecording();
    DiagnosticEventBuffer window = (DiagnosticEventBuffer) ScopeDiagnostics.recordingWindow();
    ContextContinuation continuation = Context.root().capture();
    ScopeDiagnostics.recordCapture(window, continuation, DDTraceId.from(1), 2, "op", (byte) 0);
    DiagnosticEventBuffer.Event delayed = window.reserve();
    ScopeDiagnostics.recordResolve(window, continuation, false, 1, false);
    ScopeDiagnostics.recordResolve(window, continuation, true, 2, false);
    ScopeDiagnostics.recordResolve(window, continuation, true, 3, true);
    delayed.publish(() -> {});
    ScopeDiagnosticsReport report = ScopeDiagnostics.report();
    ContinuationRecord record = report.records().get(0);
    assertEquals(ContinuationStatus.FINISHED, record.status());
    assertEquals(1, record.terminal().nanos);
    assertEquals(1, record.extraTerminals().size());
    assertEquals(3, record.extraTerminals().get(0).nanos);
    assertEquals(1, report.doubleCount());
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
    assertEquals(1, ScopeDiagnostics.report().activateAfterResolveCount());
    assertEquals(0, ScopeDiagnostics.report().leakCount());
    ScopeDiagnostics.recordResolve(window, continuation, true, System.nanoTime(), false);

    ScopeDiagnosticsReport report = ScopeDiagnostics.report();
    assertEquals(0, report.leakCount());
    assertEquals(1, report.activateAfterResolveCount());
    assertTrue(report.hasViolations());
  }

  @Test
  void failedActivationWithoutAnObservedContinuationIsRecorded() {
    ScopeDiagnostics.startRecording();
    ScopeDiagnostics.recordActivateFailed(
        ScopeDiagnostics.recordingWindow(), Context.root().capture());
    ScopeDiagnosticsReport report = ScopeDiagnostics.report();
    assertEquals(1, report.records().size());
    assertEquals(1, report.activateAfterResolveCount());
    assertEquals(0, report.leakCount());
    assertEquals(ContinuationStatus.RESOLVED, report.records().get(0).status());
    assertTrue(report.records().get(0).orphan);
  }

  @TableTest({
    "Scenario                 | Count       | Failures",
    "cancelled                | -1073741824 | 1       ",
    "concurrent failed resume | -1073741823 | 1       ",
    "duplicate cleanup        | -1073741825 | 1       ",
    "not cancelled            | 0           | 0       ",
    "activation limit         | 2147483647  | 0       "
  })
  void noopResumeRequiresCancellationEvidence(int count, int failures) {
    ScopeDiagnostics.startRecording();
    ScopeContinuationProbe.onActivate(
        Context.root().capture(), NoopScope.INSTANCE, System.nanoTime(), count);
    assertEquals(failures, ScopeDiagnostics.report().activateAfterResolveCount());
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
    assertFalse(report.hasViolations());
  }
}

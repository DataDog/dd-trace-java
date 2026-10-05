package datadog.trace.agent.test.scopediag;

import static datadog.trace.agent.test.scopediag.ScopeContinuationProbe.CANCELLED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import datadog.context.Context;
import datadog.context.ContextContinuation;
import datadog.trace.api.DDTraceId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Replays advice observations deterministically, including overlapping method invocations. */
class ScopeResolutionTest {
  private ContextContinuation continuation;

  @BeforeEach
  void start() {
    ScopeDiagnostics.startRecording();
    continuation = Context.root().capture();
    ScopeDiagnostics.recordCapture(
        ScopeDiagnostics.recordingWindow(), continuation, DDTraceId.from(1), 2, "op", (byte) 0);
  }

  @AfterEach
  void stop() {
    ScopeDiagnostics.stop();
    ScopeDiagnostics.reset();
  }

  @Test
  void overlappingReleasesAreReportedEvenWhenBothEnteredBeforeResolution() {
    Object window = ScopeDiagnostics.recordingWindow();
    // Both release entries observed zero; both exits observed CANCELLED.
    ScopeDiagnostics.recordResolve(window, continuation, true, 1, false);
    ScopeDiagnostics.recordResolve(window, continuation, true, 2, false);
    assertEquals(1, ScopeDiagnostics.report().doubleCount());
    assertEquals(0, ScopeDiagnostics.report().leakCount());
  }

  @Test
  void probeRetainsOverlappingReleaseAttempts() {
    ScopeContinuationProbe.ResolveAttempt first = enter("release", 0);
    ScopeContinuationProbe.ResolveAttempt second = enter("release", 0);
    ScopeContinuationProbe.onResolveExit(second, CANCELLED);
    ScopeContinuationProbe.onResolveExit(first, CANCELLED);
    assertEquals(1, ScopeDiagnostics.report().doubleCount());
  }

  @Test
  void nestedReleaseBelongsToTheScopeClose() {
    ScopeContinuationProbe.ResolveAttempt close = enter("cancelFromContinuedScopeClose", 2);
    ScopeContinuationProbe.ResolveAttempt release = enter("release", 0);
    ScopeContinuationProbe.onResolveExit(release, CANCELLED);
    ScopeContinuationProbe.onResolveExit(close, CANCELLED);
    assertResolvedOnce();
    assertEquals(ContinuationStatus.FINISHED, ScopeDiagnostics.report().records().get(0).status());
  }

  @Test
  void delayedSuccessfulResumeCallbackAfterCleanupIsNotRejected() {
    Object window = ScopeDiagnostics.recordingWindow();
    ScopeContinuationProbe.ResolveAttempt close = enter("cancelFromContinuedScopeClose", 1);
    ScopeContinuationProbe.onResolveExit(close, CANCELLED);
    long cleanupEntryNanos = ScopeDiagnostics.report().records().get(0).terminal().nanos;

    // The resume succeeded during cleanup, but its exit callback arrives after cleanup's callback.
    ScopeDiagnostics.recordActivate(
        window, continuation, DDTraceId.from(1), 2, "op", (byte) 0, cleanupEntryNanos + 1);

    ScopeDiagnosticsReport report = ScopeDiagnostics.report();
    assertEquals(1, report.records().get(0).resumes().size());
    assertEquals(ContinuationStatus.FINISHED, report.records().get(0).status());
    assertEquals(0, report.activateAfterResolveCount());
    assertResolvedOnce();
  }

  @Test
  void overlappingLegitimateScopeClosesResolveOnce() {
    Object window = ScopeDiagnostics.recordingWindow();
    // Separate resumed scopes may both observe CANCELLED by the time their exit advice runs.
    ScopeDiagnostics.recordResolve(window, continuation, false, 1, false);
    ScopeDiagnostics.recordResolve(window, continuation, false, 2, false);
    assertResolvedOnce();
  }

  @Test
  void holdReleaseAndLastScopeCloseCanReportInEitherOrder() {
    Object window = ScopeDiagnostics.recordingWindow();
    ScopeDiagnostics.recordResolve(window, continuation, true, 1, false);
    ScopeDiagnostics.recordResolve(window, continuation, false, 2, false);
    assertResolvedOnce();

    ScopeDiagnostics.reset();
    start();
    window = ScopeDiagnostics.recordingWindow();
    ScopeDiagnostics.recordResolve(window, continuation, false, 2, false);
    ScopeDiagnostics.recordResolve(window, continuation, true, 1, false);
    assertResolvedOnce();
  }

  @Test
  void scopeCloseDuringNestedReleaseDoesNotProduceADuplicate() {
    ScopeContinuationProbe.ResolveAttempt close = enter("cancelFromContinuedScopeClose", 2);
    ScopeContinuationProbe.ResolveAttempt release = enter("release", 0);
    // Another scope's exit advice arrives before the nested release's exit advice.
    ScopeDiagnostics.recordResolve(
        ScopeDiagnostics.recordingWindow(), continuation, false, System.nanoTime(), false);
    ScopeContinuationProbe.onResolveExit(release, CANCELLED);
    ScopeContinuationProbe.onResolveExit(close, CANCELLED);
    assertResolvedOnce();
  }

  @Test
  void resolutionEnteredInAnOldWindowCannotResolveANewCapture() {
    ScopeContinuationProbe.ResolveAttempt release = enter("release", 0);
    ScopeDiagnostics.reset();
    start();
    ScopeContinuationProbe.onResolveExit(release, CANCELLED);
    assertEquals(1, ScopeDiagnostics.report().leakCount());
    ScopeContinuationProbe.onResolveExit(enter("release", 0), CANCELLED);
    assertResolvedOnce();
  }

  @Test
  void duplicateScopeClosesWithBothExitsBelowSentinelAreNotReportedAsLeaked() {
    ScopeContinuationProbe.ResolveAttempt first = enter("cancelFromContinuedScopeClose", 1);
    ScopeContinuationProbe.ResolveAttempt second = enter("cancelFromContinuedScopeClose", 1);
    ScopeContinuationProbe.onResolveExit(second, CANCELLED - 1);
    ScopeContinuationProbe.onResolveExit(first, CANCELLED - 1);
    assertDuplicateWithoutLeak();
  }

  @Test
  void duplicateScopeCloseAfterSuccessfulCloseIsReported() {
    ScopeContinuationProbe.ResolveAttempt first = enter("cancelFromContinuedScopeClose", 1);
    ScopeContinuationProbe.ResolveAttempt second = enter("cancelFromContinuedScopeClose", 1);
    ScopeContinuationProbe.onResolveExit(second, CANCELLED);
    ScopeContinuationProbe.onResolveExit(first, CANCELLED - 1);
    assertDuplicateWithoutLeak();
  }

  @Test
  void underflowBeforeSuccessfulCloseAdviceIsAlreadyDuplicateEvidence() {
    ScopeContinuationProbe.ResolveAttempt first = enter("cancelFromContinuedScopeClose", 1);
    ScopeContinuationProbe.ResolveAttempt second = enter("cancelFromContinuedScopeClose", 1);
    ScopeContinuationProbe.onResolveExit(second, CANCELLED - 1);
    try {
      assertDuplicateWithoutLeak();
    } finally {
      // The successful close sampled CANCELLED, but its recorder callback was delayed.
      ScopeContinuationProbe.onResolveExit(first, CANCELLED);
    }
    assertDuplicateWithoutLeak();
  }

  @Test
  void furtherCloseAfterCounterUnderflowRemainsAReportedDuplicate() {
    ScopeContinuationProbe.onResolveExit(enter("cancelFromContinuedScopeClose", 1), CANCELLED);
    ScopeContinuationProbe.onResolveExit(
        enter("cancelFromContinuedScopeClose", CANCELLED - 1), CANCELLED - 2);
    assertDuplicateWithoutLeak();
  }

  @Test
  void legitimateScopeClosesObservingTheSameTerminalStateAreNotDuplicates() {
    ScopeContinuationProbe.ResolveAttempt first = enter("cancelFromContinuedScopeClose", 2);
    ScopeContinuationProbe.ResolveAttempt second = enter("cancelFromContinuedScopeClose", 1);
    ScopeContinuationProbe.onResolveExit(second, CANCELLED);
    ScopeContinuationProbe.onResolveExit(first, CANCELLED);
    assertResolvedOnce();
  }

  private ScopeContinuationProbe.ResolveAttempt enter(String method, int count) {
    return ScopeContinuationProbe.onResolveEnter(continuation, method, count);
  }

  private void assertDuplicateWithoutLeak() {
    ScopeDiagnosticsReport report = ScopeDiagnostics.report();
    assertEquals(0, report.leakCount());
    assertEquals(1, report.doubleCount());
  }

  private void assertResolvedOnce() {
    assertEquals(0, ScopeDiagnostics.report().leakCount());
    assertFalse(ScopeDiagnostics.report().hasProblems());
  }
}

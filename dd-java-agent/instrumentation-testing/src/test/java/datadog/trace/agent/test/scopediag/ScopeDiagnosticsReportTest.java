package datadog.trace.agent.test.scopediag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.DDTraceId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ScopeDiagnosticsReportTest {

  private static final StackTraceElement[] STACK = {
    new StackTraceElement("com.app.Worker", "submit", "Worker.java", 42)
  };

  private static ScopeEvent event(ScopeEvent.Type type, String thread, long nanos) {
    return new ScopeEvent(type, thread, nanos, STACK);
  }

  private static ContinuationRecord record(long seq, DDTraceId trace) {
    return new ContinuationRecord(
        seq, trace, 7L, "op", (byte) 0, false, event(ScopeEvent.Type.CAPTURE, "main", 1000));
  }

  @Test
  void resolvedContinuationHasNoFailures() {
    ContinuationRecord r = record(0, DDTraceId.from(10));
    r.addResume(event(ScopeEvent.Type.ACTIVATE, "pool-1", 2000));
    r.setTerminalOrExtra(event(ScopeEvent.Type.RESOLVE_FINISH, "pool-1", 3000));

    ScopeDiagnosticsReport report = report(list(r), map());

    assertEquals(0, report.leakCount());
    assertEquals(0, report.lateCount());
    assertEquals(0, report.doubleCount());
    assertEquals(ContinuationStatus.FINISHED, r.status());
    assertTrue(r.threadHandoff()); // captured on main, resolved on pool-1
    assertFalse(report.hasProblems());
  }

  @Test
  void neverResolvedIsFlaggedAsLeak() {
    ContinuationRecord r = record(0, DDTraceId.from(11));

    ScopeDiagnosticsReport report = report(list(r), map());

    assertEquals(1, report.leakCount());
    assertEquals(ContinuationStatus.LEAKED, r.status());
    assertTrue(report.hasProblems());
    assertTrue(report.renderSummary().contains("LEAKED"));
    // the capture callsite is surfaced in the problem summary
    assertTrue(report.renderSummary().contains("Worker.java:42"));
  }

  @Test
  void resolutionAfterRootWriteIsFlaggedLate() {
    DDTraceId trace = DDTraceId.from(12);
    ContinuationRecord r = record(0, trace);
    r.addResume(event(ScopeEvent.Type.ACTIVATE, "pool-1", 5000));
    r.setTerminalOrExtra(event(ScopeEvent.Type.RESOLVE_FINISH, "pool-1", 6000));

    Map<DDTraceId, Long> rootWritten = map();
    rootWritten.put(trace, 4000L); // root written before the activation/resolution

    ScopeDiagnosticsReport report = report(list(r), rootWritten);

    assertEquals(1, report.lateCount());
    assertEquals(0, report.leakCount()); // it is resolved, just late
  }

  @Test
  void lateFinishDoesNotFail() {
    DDTraceId trace = DDTraceId.from(120);
    ContinuationRecord r = record(0, trace);
    r.addResume(event(ScopeEvent.Type.ACTIVATE, "pool-1", 5000));
    r.setTerminalOrExtra(event(ScopeEvent.Type.RESOLVE_FINISH, "pool-1", 6000));
    Map<DDTraceId, Long> rootWritten = map();
    rootWritten.put(trace, 4000L);

    ScopeDiagnosticsReport report = report(list(r), rootWritten);

    assertEquals(1, report.lateCount());
    assertFalse(report.hasProblems()); // late-finish is report-only
  }

  @Test
  void multipleResolutionsAreFlaggedDouble() {
    ContinuationRecord r = record(0, DDTraceId.from(13));
    r.addResume(event(ScopeEvent.Type.ACTIVATE, "pool-1", 2000));
    r.setTerminalOrExtra(event(ScopeEvent.Type.RESOLVE_FINISH, "pool-1", 3000));
    r.setTerminalOrExtra(event(ScopeEvent.Type.RESOLVE_FINISH, "pool-1", 3500));

    ScopeDiagnosticsReport report = report(list(r), map());

    assertEquals(1, report.doubleCount());
    assertTrue(report.hasProblems());
  }

  @Test
  void activationAfterResolveIsFailure() {
    ContinuationRecord r = record(0, DDTraceId.from(14));
    r.setTerminalOrExtra(event(ScopeEvent.Type.RESOLVE_CANCEL, "pool-1", 2000));
    r.addResume(event(ScopeEvent.Type.ACTIVATE, "pool-2", 3000)); // resume after cancel

    ScopeDiagnosticsReport report = report(list(r), map());

    assertEquals(1, report.activateAfterResolveCount());
    assertEquals(0, report.doubleCount());
    assertTrue(report.hasProblems());
  }

  @Test
  void failedActivationIsActivateAfterResolve() {
    ContinuationRecord r = record(0, DDTraceId.from(141));
    r.setTerminalOrExtra(event(ScopeEvent.Type.RESOLVE_CANCEL, "pool-1", 2000));
    r.addFailedActivation(event(ScopeEvent.Type.ACTIVATE_FAILED, "pool-2", 3000));

    ScopeDiagnosticsReport report = report(list(r), map());

    assertEquals(1, report.activateAfterResolveCount());
    assertTrue(report.hasProblems());
  }

  // ---- helpers -------------------------------------------------------------

  private static ScopeDiagnosticsReport report(
      List<ContinuationRecord> records, Map<DDTraceId, Long> rootWritten) {
    return new ScopeDiagnosticsReport(records, new ArrayList<>(), rootWritten);
  }

  private static List<ContinuationRecord> list(ContinuationRecord... rs) {
    List<ContinuationRecord> l = new ArrayList<>();
    for (ContinuationRecord r : rs) {
      l.add(r);
    }
    return l;
  }

  private static Map<DDTraceId, Long> map() {
    return new HashMap<>();
  }
}

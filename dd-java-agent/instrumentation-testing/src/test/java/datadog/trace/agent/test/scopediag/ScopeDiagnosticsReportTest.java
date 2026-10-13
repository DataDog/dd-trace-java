package datadog.trace.agent.test.scopediag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.DDTraceId;
import java.util.ArrayList;
import java.util.EnumSet;
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

  @TrackScopeContinuations(
      disabledChecks = ScopeDiagnosticsCheck.LEAKED,
      reason = "synthetic report tests exclusion")
  private static class ExcludeLeaks {}

  @Test
  void firstResumeTimingUsesEarliestTimestampRegardlessOfRecordingOrder() {
    ContinuationRecord r = record(0, DDTraceId.from(10));
    r.addResume(event(ScopeEvent.Type.ACTIVATE, "pool-2", 3000));
    r.addResume(event(ScopeEvent.Type.ACTIVATE, "pool-1", 2000));

    assertEquals(Long.valueOf(1000), r.captureToFirstResumeNanos());
    assertEquals(Long.valueOf(1000), r.snapshot().captureToFirstResumeNanos());
  }

  @Test
  void firstResumeTimingRequiresCaptureAndResume() {
    ContinuationRecord captured = record(0, DDTraceId.from(10));
    assertNull(captured.captureToFirstResumeNanos());

    ContinuationRecord orphan =
        new ContinuationRecord(1, DDTraceId.from(10), 7L, "op", (byte) 0, true, null);
    orphan.addResume(event(ScopeEvent.Type.ACTIVATE, "pool-1", 2000));
    assertNull(orphan.captureToFirstResumeNanos());
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
    assertTrue(r.threadHandoff());
    assertFalse(report.hasViolations());
  }

  @Test
  void neverResolvedIsFlaggedAsLeak() {
    ContinuationRecord r = record(0, DDTraceId.from(11));

    ScopeDiagnosticsReport report = report(list(r), map());

    assertEquals(1, report.leakCount());
    assertEquals(ContinuationStatus.LEAKED, r.status());
    assertTrue(report.hasViolations());
    assertTrue(report.renderSummary().contains("LEAKED"));
    assertTrue(report.renderSummary().contains("Worker.java:42"));
  }

  @Test
  void resolutionAfterRootWriteIsFlaggedLate() {
    DDTraceId trace = DDTraceId.from(12);
    ContinuationRecord r = record(0, trace);
    r.addResume(event(ScopeEvent.Type.ACTIVATE, "pool-1", 5000));
    r.setTerminalOrExtra(event(ScopeEvent.Type.RESOLVE_FINISH, "pool-1", 6000));

    Map<DDTraceId, Long> rootWritten = map();
    rootWritten.put(trace, 4000L);

    ScopeDiagnosticsReport report = report(list(r), rootWritten);

    assertEquals(1, report.lateCount());
    assertEquals(0, report.leakCount());
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
    assertFalse(report.hasViolations());
    assertFalse(report.hasViolations(EnumSet.of(ScopeDiagnosticsCheck.LATE_FINISH)));
  }

  @Test
  void enabledChecksSelectEnforcementWithoutChangingFindings() {
    ContinuationRecord r = record(0, DDTraceId.from(121));
    ScopeDiagnosticsReport report = report(list(r), map());

    assertTrue(report.hasViolations(EnumSet.of(ScopeDiagnosticsCheck.LEAKED)));
    assertFalse(report.hasViolations(EnumSet.of(ScopeDiagnosticsCheck.DOUBLE_FINISH)));
    assertEquals(1, report.leakCount());
    assertTrue(report.hasFindings());
    assertTrue(report.renderSummary().contains("LEAKED"));
  }

  @Test
  void assertionSeparatesEnforcedAdvisoryAndExcludedFindings() {
    DDTraceId trace = DDTraceId.from(122);
    ContinuationRecord leaked = record(0, trace);
    ContinuationRecord doubled = record(1, trace);
    doubled.setTerminalOrExtra(event(ScopeEvent.Type.RESOLVE_FINISH, "main", 3000));
    doubled.setTerminalOrExtra(event(ScopeEvent.Type.RESOLVE_FINISH, "main", 4000));
    Map<DDTraceId, Long> rootWritten = map();
    rootWritten.put(trace, 2000L);
    List<ContinuationRecord> records = list(leaked);
    records.add(doubled);
    ScopeDiagnosticsReport report = report(records, rootWritten);
    TrackScopeContinuations config =
        ExcludeLeaks.class.getAnnotation(TrackScopeContinuations.class);

    AssertionError failure =
        assertThrows(
            AssertionError.class, () -> ScopeDiagnostics.assertNoViolations(report, config));
    String message = failure.getMessage();
    assertTrue(message.contains("[DOUBLE_FINISH] #1"));
    assertTrue(message.contains("[LATE_FINISH] #1"));
    assertTrue(message.contains("[LEAKED] #0"));
    int advisory = message.indexOf("Advisory findings (not enforced)");
    int excluded = message.indexOf("Excluded findings (not enforced)");
    assertTrue(message.indexOf("[DOUBLE_FINISH] #1") < advisory);
    assertTrue(message.indexOf("[LATE_FINISH] #1") > advisory);
    assertTrue(message.indexOf("[LATE_FINISH] #1") < excluded);
    assertTrue(message.indexOf("[LEAKED] #0") > excluded);
    assertEquals(1, report.leakCount());
    assertEquals(1, report.doubleCount());
    assertTrue(report.renderTimeline().contains("LEAKED"));
  }

  @Test
  void multipleResolutionsAreFlaggedDouble() {
    ContinuationRecord r = record(0, DDTraceId.from(13));
    r.addResume(event(ScopeEvent.Type.ACTIVATE, "pool-1", 2000));
    r.setTerminalOrExtra(event(ScopeEvent.Type.RESOLVE_FINISH, "pool-1", 3000));
    r.setTerminalOrExtra(event(ScopeEvent.Type.RESOLVE_FINISH, "pool-1", 3500));

    ScopeDiagnosticsReport report = report(list(r), map());

    assertEquals(1, report.doubleCount());
    assertTrue(report.hasViolations());
  }

  @Test
  void successfulResumeAfterCleanupEntryIsNotActivationAfterResolve() {
    ContinuationRecord r = record(0, DDTraceId.from(14));
    r.addResume(event(ScopeEvent.Type.ACTIVATE, "pool-1", 1500));
    r.addResume(event(ScopeEvent.Type.ACTIVATE, "pool-2", 3000));
    r.setTerminalOrExtra(event(ScopeEvent.Type.RESOLVE_FINISH, "pool-1", 2000));

    ScopeDiagnosticsReport report = report(list(r), map());

    assertEquals(2, report.records().get(0).resumes().size());
    assertEquals(ContinuationStatus.FINISHED, report.records().get(0).status());
    assertEquals(0, report.activateAfterResolveCount());
    assertEquals(0, report.doubleCount());
    assertFalse(report.hasViolations());
  }

  @Test
  void failedActivationIsActivateAfterResolve() {
    ContinuationRecord r = record(0, DDTraceId.from(141));
    r.setTerminalOrExtra(event(ScopeEvent.Type.RESOLVE_RELEASE, "pool-1", 2000));
    r.addFailedActivation(event(ScopeEvent.Type.ACTIVATE_FAILED, "pool-2", 3000));

    ScopeDiagnosticsReport report = report(list(r), map());

    assertEquals(1, report.activateAfterResolveCount());
    assertTrue(report.hasViolations());
  }

  @Test
  void timelineRendersResolvedContinuationEvenWithoutProblems() {
    ContinuationRecord r = record(0, DDTraceId.from(30));
    r.addResume(event(ScopeEvent.Type.ACTIVATE, "pool-1", 2000));
    r.setTerminalOrExtra(event(ScopeEvent.Type.RESOLVE_FINISH, "pool-1", 3000));

    ScopeDiagnosticsReport report = report(list(r), map());

    assertFalse(report.hasViolations());
    assertTrue(report.renderSummary().contains("(none)"));

    String timeline = report.renderTimeline();
    assertTrue(timeline.contains("#0 FINISHED"));
    assertTrue(timeline.contains("capture"));
    assertTrue(timeline.contains("resume"));
    assertTrue(timeline.contains("finish"));
    assertTrue(timeline.contains("Worker.java:42"));
    assertTrue(timeline.contains("@ pool-1"));
  }

  @Test
  void reportIsAnImmutableSnapshot() {
    ContinuationRecord record = record(0, DDTraceId.from(31));
    ScopeDiagnosticsReport report = report(list(record), map());

    record.setTerminalOrExtra(event(ScopeEvent.Type.RESOLVE_FINISH, "pool-1", 3000));

    assertEquals(1, report.leakCount());
    assertEquals(ContinuationStatus.LEAKED, report.records().get(0).status());
  }

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

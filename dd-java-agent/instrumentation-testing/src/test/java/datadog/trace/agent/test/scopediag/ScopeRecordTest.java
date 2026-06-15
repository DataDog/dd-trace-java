package datadog.trace.agent.test.scopediag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.DDTraceId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScopeRecordTest {

  private static final StackTraceElement[] STACK = {
    new StackTraceElement("com.app.Worker", "run", "Worker.java", 7)
  };

  private static ScopeEvent event(ScopeEvent.Type type, String thread, long nanos) {
    return new ScopeEvent(type, thread, nanos, STACK);
  }

  private static ScopeRecord scope(long seq, Long continuationSeq, String openThread, long nanos) {
    return new ScopeRecord(
        seq,
        DDTraceId.from(1),
        9L,
        "op",
        (byte) 0,
        continuationSeq,
        event(ScopeEvent.Type.SCOPE_OPEN, openThread, nanos));
  }

  private static ScopeDiagnosticsReport report(ScopeRecord... scopes) {
    List<ScopeRecord> list = new ArrayList<>();
    for (ScopeRecord s : scopes) {
      list.add(s);
    }
    return new ScopeDiagnosticsReport(new ArrayList<>(), list, new HashMap<>());
  }

  @Test
  void openAndClosedHasNoFailures() {
    ScopeRecord s = scope(0, null, "main", 1000);
    s.setClose(event(ScopeEvent.Type.SCOPE_CLOSE, "main", 3000));

    assertTrue(s.closed());
    assertEquals(0, s.failures().size());
    assertFalse(s.threadHandoff());
    assertEquals(Long.valueOf(2000), s.activeDurationNanos());
    assertFalse(report(s).hasProblems());
  }

  @Test
  void openWithoutCloseIsNeverClosed() {
    ScopeRecord s = scope(0, null, "main", 1000);

    assertFalse(s.closed());
    assertTrue(s.failures().contains(Failure.NEVER_CLOSED));

    ScopeDiagnosticsReport report = report(s);
    assertEquals(1, report.neverClosedScopeCount());
    assertTrue(report.hasProblems()); // never-closed is a genuine bug
  }

  @Test
  void openAndCloseOnDifferentThreadsIsHandoff() {
    ScopeRecord s = scope(0, null, "main", 1000);
    s.setClose(event(ScopeEvent.Type.SCOPE_CLOSE, "pool-1", 2000));

    assertTrue(s.threadHandoff());
  }

  @Test
  void wrongThreadCloseIsReportedButDoesNotFail() {
    ScopeRecord s = scope(0, null, "main", 1000);
    s.setClose(event(ScopeEvent.Type.SCOPE_CLOSE, "main", 2000));
    s.addWrongThreadClose(event(ScopeEvent.Type.SCOPE_CLOSE_WRONG_THREAD, "pool-2", 1500));

    assertTrue(s.failures().contains(Failure.CLOSE_WRONG_THREAD));

    ScopeDiagnosticsReport report = report(s);
    assertEquals(1, report.closeWrongThreadCount());
    assertFalse(report.hasProblems()); // wrong-thread is report-only
  }

  @Test
  void jsonContainsScopeFields() {
    ScopeRecord s = scope(3, 1L, "main", 1000);
    s.setClose(event(ScopeEvent.Type.SCOPE_CLOSE, "main", 2000));

    String json = report(s).toJson();
    assertTrue(json.contains("\"scopes\":["));
    assertTrue(json.contains("\"continuationSeq\":1"));
    assertTrue(json.contains("\"closed\":true"));
  }
}

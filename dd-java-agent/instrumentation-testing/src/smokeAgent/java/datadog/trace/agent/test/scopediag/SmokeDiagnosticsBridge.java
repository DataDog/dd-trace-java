package datadog.trace.agent.test.scopediag;

import java.lang.instrument.Instrumentation;
import java.util.EnumSet;
import java.util.Map;
import java.util.Properties;
import net.bytebuddy.dynamic.ClassFileLocator;

/** Runs in the tracer's loader so all recorded objects retain their actual type identity. */
public final class SmokeDiagnosticsBridge {
  private static Properties completed;
  private static boolean recording;
  private static String exclusionReason = "";
  private static EnumSet<ScopeDiagnosticsCheck> enabledChecks =
      EnumSet.allOf(ScopeDiagnosticsCheck.class);

  private SmokeDiagnosticsBridge() {}

  public static void install(
      Instrumentation instrumentation,
      Map<String, byte[]> classes,
      String disabledChecks,
      String reason) {
    EnumSet<ScopeDiagnosticsCheck> checks = EnumSet.allOf(ScopeDiagnosticsCheck.class);
    if (!disabledChecks.isEmpty()) {
      if (reason.trim().isEmpty()) {
        throw new IllegalArgumentException("Disabling scope diagnostic checks requires a reason");
      }
      for (String check : disabledChecks.split(",", -1)) {
        checks.remove(ScopeDiagnosticsCheck.valueOf(check.trim()));
      }
    }
    enabledChecks = checks;
    exclusionReason = reason;
    ScopeContinuationTransformer.install(
        instrumentation, "datadog.trace.agent.core", new ClassFileLocator.Simple(classes));
  }

  public static synchronized void start() {
    if (recording) {
      throw new IllegalStateException("Scope diagnostics are already recording");
    }
    completed = null;
    ScopeDiagnostics.startRecording();
    recording = true;
  }

  public static synchronized Properties finish() {
    if (completed != null) {
      return completed;
    }
    if (!recording) {
      throw new IllegalStateException("Scope diagnostics have not started recording");
    }
    ScopeDiagnostics.awaitQuiescence();
    ScopeDiagnostics.stop();
    recording = false;
    ScopeDiagnosticsReport report = ScopeDiagnostics.report();
    Properties result = new Properties();
    result.setProperty("status", report.hasViolations(enabledChecks) ? "problems" : "ok");
    result.setProperty("detail", report.renderSummary(enabledChecks));
    result.setProperty("timeline", report.renderTimeline());
    result.setProperty("enabledChecks", enabledChecks.toString());
    result.setProperty("reason", exclusionReason);
    long events = 0;
    for (ContinuationRecord record : report.records()) {
      events +=
          (record.capture() == null ? 0 : 1)
              + record.resumes().size()
              + record.failedActivations().size()
              + (record.terminal() == null ? 0 : 1)
              + record.extraTerminals().size();
    }
    for (ScopeRecord record : report.scopeRecords()) {
      events +=
          (record.open() == null ? 0 : 1)
              + (record.close() == null ? 0 : 1)
              + record.wrongThreadCloses().size();
    }
    result.setProperty("eventCount", Long.toString(events));
    completed = result;
    return result;
  }
}

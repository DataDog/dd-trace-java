package datadog.trace.api.telemetry;

import datadog.trace.util.stacktrace.StackUtils;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import org.slf4j.helpers.MessageFormatter;

public class LogCollector {
  public static final int MAX_ENTRIES = 10000;
  private static int overflowedEntryCount = 0;
  private static boolean enabled = false;
  public static boolean debugEnabled = false;
  private static LinkedHashSet<TelemetryLogEntry> logEntries = new LinkedHashSet<>();
  private static String[] packageList = {"datadog.", "com.datadog.", "java.", "javax."};
  private static final String RET = "\r\n";

  public static class Holder {
    public static final LogCollector INSTANCE = new LogCollector();
  }

  public static LogCollector get() {
    return Holder.INSTANCE;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public void setDebugEnabled(boolean enabled) {
    debugEnabled = enabled;
  }

  public boolean isEnabled() {
    return enabled;
  }

  public boolean isDebugEnabled() {
    return debugEnabled;
  }

  public synchronized void addLogEntry(
      String msg,
      String level,
      Throwable t,
      String format,
      Object arg1,
      Object arg2,
      Object[] args) {
    if (null == msg && null == format) {
      return;
    }
    if (logEntries.size() >= MAX_ENTRIES) {
      overflowedEntryCount++;
      return;
    }

    boolean isDDCode = false;
    TelemetryLogEntry log = new TelemetryLogEntry();
    if (null != format) {
      t = MessageFormatter.getThrowableCandidate(args);
    }
    if (null != t) {
      isDDCode = isDataDogCode(t);
      log.setStackTrace(renderStackTrace(t, isDDCode));
    }
    if (null != msg) {
      log.setMessage(msg);
    } else {
      if (null != format) {
        if (isDDCode) {
          log.setMessage(MessageFormatter.arrayFormat(format, args).getMessage());
        } else {
          log.setMessage(format);
        }
      }
    }
    log.setLevel(level);
    logEntries.add(log);
  }

  private String renderStackTrace(Throwable t, boolean isDataDogCode) {
    StringBuilder stackTrace = new StringBuilder();
    stackTrace.append(t.getClass().getCanonicalName());

    String firstStackElementClassName = t.getStackTrace()[0].getClassName();
    if (isDataDogCode) {
      stackTrace.append(": ");
      stackTrace.append(t.getMessage());
    }
    stackTrace.append(RET);

    StackUtils.filterPackagesIn(t, packageList);
    for (StackTraceElement stackTraceElement : t.getStackTrace()) {
      stackTrace.append("  at ");
      stackTrace.append(stackTraceElement.toString());
      stackTrace.append(RET);
    }
    return stackTrace.toString();
  }

  boolean isDataDogCode(Throwable t) {
    String firstStackElementClassName = t.getStackTrace()[0].getClassName();
    return firstStackElementClassName.startsWith("datadog.")
        || firstStackElementClassName.startsWith("com.datadog.");
  }

  public synchronized List<TelemetryLogEntry> drain() {
    if (logEntries.isEmpty()) {
      return Collections.emptyList();
    }

    ArrayList<TelemetryLogEntry> drainedEntries = new ArrayList<>();

    drainedEntries.addAll(logEntries);

    if (overflowedEntryCount > 0) {
      TelemetryLogEntry overflowErrorEntry = new TelemetryLogEntry();
      overflowErrorEntry.setMessage(
          "Omitted " + overflowedEntryCount + " entries due to overflowing");
      overflowErrorEntry.setLevel("ERROR");
      drainedEntries.add(overflowErrorEntry);
    }

    overflowedEntryCount = 0;
    logEntries.clear();
    return drainedEntries;
  }
}

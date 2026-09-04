package datadog.trace.core.otlp.logs;

import datadog.communication.otlp.OtlpPayload;

/** Collects logs ready for export. */
public abstract class OtlpLogsCollector {

  /** Waits for logs to be batched within the given interval. */
  public abstract OtlpPayload waitForLogs(int intervalMillis);

  /** Number of log records collected. */
  public abstract int getLogRecordCount();
}

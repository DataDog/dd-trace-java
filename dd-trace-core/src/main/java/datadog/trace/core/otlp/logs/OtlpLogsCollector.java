package datadog.trace.core.otlp.logs;

import datadog.trace.core.otlp.common.OtlpPayload;

/** Collects logs ready for export. */
public abstract class OtlpLogsCollector {

  /** Waits for logs to be batched within the given interval. */
  public abstract OtlpPayload waitForLogs(int intervalMillis);
}

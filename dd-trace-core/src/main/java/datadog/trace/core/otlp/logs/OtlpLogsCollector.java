package datadog.trace.core.otlp.logs;

import datadog.trace.core.otlp.common.OtlpPayload;

/** Collects logs ready for export. */
public abstract class OtlpLogsCollector {
  public abstract OtlpPayload collectLogs();
}

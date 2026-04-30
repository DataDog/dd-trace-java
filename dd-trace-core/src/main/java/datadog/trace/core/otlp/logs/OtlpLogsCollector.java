package datadog.trace.core.otlp.logs;

import datadog.trace.core.otlp.common.OtlpPayload;

/** Collects logs ready for export. */
public interface OtlpLogsCollector {
  OtlpLogsCollector NOOP_COLLECTOR = () -> OtlpPayload.EMPTY;

  OtlpPayload collectLogs();
}

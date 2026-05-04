package datadog.trace.core.otlp.logs;

import datadog.trace.core.otlp.common.OtlpPayload;

final class NoopOtlpLogsCollector extends OtlpLogsCollector {
  static final NoopOtlpLogsCollector INSTANCE = new NoopOtlpLogsCollector();

  public OtlpPayload collectLogs() {
    return OtlpPayload.EMPTY;
  }
}

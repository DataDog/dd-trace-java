package datadog.trace.bootstrap.otlp.logs;

import datadog.trace.bootstrap.otel.common.OtelInstrumentationScope;

/** A visitor to visit OpenTelemetry logs. */
public interface OtlpLogsVisitor {
  /** Visits logs produced by an instrumentation scope. */
  OtlpScopedLogsVisitor visitScopedLogs(OtelInstrumentationScope scope);
}

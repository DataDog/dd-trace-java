package datadog.opentelemetry.shim.logs;

import datadog.trace.bootstrap.otel.common.OtelInstrumentationScope;
import io.opentelemetry.api.logs.LogRecordBuilder;
import io.opentelemetry.api.logs.Logger;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.context.Context;
import javax.annotation.ParametersAreNonnullByDefault;

@ParametersAreNonnullByDefault
final class OtelLogger implements Logger {
  final OtelInstrumentationScope instrumentationScope;

  OtelLogger(OtelInstrumentationScope instrumentationScope) {
    this.instrumentationScope = instrumentationScope;
  }

  @Override
  public LogRecordBuilder logRecordBuilder() {
    return new OtelLogRecordBuilder(this);
  }

  public boolean isEnabled(Severity severity, Context context) {
    return true;
  }

  @Override
  public String toString() {
    return "OtelLogger{instrumentationScope=" + instrumentationScope + "}";
  }
}

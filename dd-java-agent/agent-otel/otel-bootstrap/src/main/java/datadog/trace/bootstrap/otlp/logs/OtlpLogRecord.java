package datadog.trace.bootstrap.otlp.logs;

import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.otel.common.OtelInstrumentationScope;
import java.util.Map;
import javax.annotation.Nullable;

public final class OtlpLogRecord {

  public final OtelInstrumentationScope instrumentationScope;

  public final long timestampNanos;
  public final long observedNanos;
  public final int severityNumber;
  @Nullable public final String severityText;
  @Nullable public final String body;
  public final Map<?, ?> attributes;
  @Nullable public final AgentSpanContext spanContext;
  @Nullable public final String eventName;

  public OtlpLogRecord(
      OtelInstrumentationScope instrumentationScope,
      long timestampNanos,
      long observedNanos,
      int severityNumber,
      @Nullable String severityText,
      @Nullable String body,
      Map<?, ?> attributes,
      @Nullable AgentSpanContext spanContext,
      @Nullable String eventName) {
    this.instrumentationScope = instrumentationScope;
    this.timestampNanos = timestampNanos;
    this.observedNanos = observedNanos;
    this.severityNumber = severityNumber;
    this.severityText = severityText;
    this.body = body;
    this.attributes = attributes;
    this.spanContext = spanContext;
    this.eventName = eventName;
  }
}

package datadog.trace.bootstrap.otlp.logs;

import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.otel.common.OtelInstrumentationScope;
import java.util.Map;
import javax.annotation.Nullable;

public final class OtlpLogRecord {

  public static final int STRING_BODY = 0; // ValueType.STRING
  public static final int BOOLEAN_BODY = 1; // ValueType.BOOLEAN
  public static final int LONG_BODY = 2; // ValueType.LONG
  public static final int DOUBLE_BODY = 3; // ValueType.DOUBLE
  public static final int ARRAY_BODY = 4; // ValueType.ARRAY
  public static final int KEY_VALUE_LIST_BODY = 5; // ValueType.KEY_VALUE_LIST
  public static final int BYTES_BODY = 6; // ValueType.BYTES

  public final OtelInstrumentationScope instrumentationScope;

  public final long timestampNanos;
  public final long observedNanos;
  public final int severityNumber;
  @Nullable public final String severityText;
  public final int bodyType;
  @Nullable public final Object bodyValue;
  @Nullable public final String eventName;
  public final Map<?, ?> attributes;
  @Nullable public final AgentSpanContext spanContext;

  public OtlpLogRecord(
      OtelInstrumentationScope instrumentationScope,
      long timestampNanos,
      long observedNanos,
      int severityNumber,
      @Nullable String severityText,
      int bodyType,
      @Nullable Object bodyValue,
      @Nullable String eventName,
      Map<?, ?> attributes,
      @Nullable AgentSpanContext spanContext) {
    this.instrumentationScope = instrumentationScope;
    this.timestampNanos = timestampNanos;
    this.observedNanos = observedNanos;
    this.severityNumber = severityNumber;
    this.severityText = severityText;
    this.bodyType = bodyType;
    this.bodyValue = bodyValue;
    this.eventName = eventName;
    this.attributes = attributes;
    this.spanContext = spanContext;
  }
}

package datadog.trace.instrumentation.opentelemetry14.trace;

import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTraceId;
import datadog.trace.bootstrap.instrumentation.api.SpanLink;
import datadog.trace.bootstrap.instrumentation.api.SpanLinkAttributes;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceState;
import java.util.List;

public class OtelSpanLink extends SpanLink {
  private static final int TRACESTATE_MAX_SIZE = 512;
  private static final char TRACESTATE_ENTRY_DELIMITER = ',';
  private static final char TRACESTATE_KEY_VALUE_DELIMITER = '=';

  public OtelSpanLink(SpanContext spanContext) {
    this(spanContext, io.opentelemetry.api.common.Attributes.empty());
  }

  public OtelSpanLink(SpanContext spanContext, io.opentelemetry.api.common.Attributes attributes) {
    super(
        DDTraceId.fromHex(spanContext.getTraceId()),
        DDSpanId.fromHex(spanContext.getSpanId()),
        spanContext.isSampled() ? SAMPLED_FLAG : DEFAULT_FLAGS,
        encodeTraceState(spanContext.getTraceState()),
        convertAttributes(attributes));
  }

  // Inspired from W3CTraceContextEncoding.encodeTraceState only available in API later versions.
  private static String encodeTraceState(TraceState traceState) {
    if (traceState.isEmpty()) {
      return "";
    }
    StringBuilder builder = new StringBuilder(TRACESTATE_MAX_SIZE);
    traceState.forEach(
        (key, value) -> {
          if (builder.length() != 0) {
            builder.append(TRACESTATE_ENTRY_DELIMITER);
          }
          builder.append(key).append(TRACESTATE_KEY_VALUE_DELIMITER).append(value);
        });
    return builder.toString();
  }

  private static Attributes convertAttributes(io.opentelemetry.api.common.Attributes attributes) {
    if (attributes.isEmpty()) {
      return SpanLinkAttributes.EMPTY;
    }
    SpanLinkAttributes.Builder builder = SpanLinkAttributes.builder();
    attributes.forEach(
        (attributeKey, value) -> {
          String key = attributeKey.getKey();
          switch (attributeKey.getType()) {
            case STRING:
              builder.put(key, (String) value);
              break;
            case BOOLEAN:
              builder.put(key, (boolean) value);
              break;
            case LONG:
              builder.put(key, (long) value);
              break;
            case DOUBLE:
              builder.put(key, (double) value);
              break;
            case STRING_ARRAY:
              //noinspection unchecked
              builder.putStringArray(key, (List<String>) value);
              break;
            case BOOLEAN_ARRAY:
              //noinspection unchecked
              builder.putBooleanArray(key, (List<Boolean>) value);
              break;
            case LONG_ARRAY:
              //noinspection unchecked
              builder.putLongArray(key, (List<Long>) value);
              break;
            case DOUBLE_ARRAY:
              //noinspection unchecked
              builder.putDoubleArray(key, (List<Double>) value);
              break;
          }
        });
    return builder.build();
  }
}

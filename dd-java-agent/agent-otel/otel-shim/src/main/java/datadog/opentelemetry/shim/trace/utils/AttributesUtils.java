package datadog.opentelemetry.shim.trace.utils;

import datadog.trace.bootstrap.instrumentation.api.AgentSpanAttributes;
import datadog.trace.bootstrap.instrumentation.api.SpanAttributes;
import java.util.List;

public class AttributesUtils {
  public static AgentSpanAttributes convertAttributes(
      io.opentelemetry.api.common.Attributes attributes) {
    if (attributes.isEmpty()) {
      return SpanAttributes.EMPTY;
    }
    SpanAttributes.Builder builder = SpanAttributes.builder();
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

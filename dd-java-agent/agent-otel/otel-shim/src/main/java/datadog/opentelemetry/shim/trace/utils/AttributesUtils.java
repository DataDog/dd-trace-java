package datadog.opentelemetry.shim.trace.utils;

import datadog.trace.bootstrap.instrumentation.api.Attributes;
import datadog.trace.bootstrap.instrumentation.api.OtelSDKAttribute;
import java.util.List;

public class AttributesUtils {
  public static Attributes convertAttributes(io.opentelemetry.api.common.Attributes attributes) {
    if (attributes.isEmpty()) {
      return OtelSDKAttribute.EMPTY;
    }
    OtelSDKAttribute.Builder builder = OtelSDKAttribute.builder();
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

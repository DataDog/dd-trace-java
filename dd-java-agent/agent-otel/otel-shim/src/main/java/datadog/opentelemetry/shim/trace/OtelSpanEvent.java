package datadog.opentelemetry.shim.trace;

import datadog.trace.api.time.SystemTimeSource;
import datadog.trace.api.time.TimeSource;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.opentelemetry.api.common.AttributeKey;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

public class OtelSpanEvent {

  private final long timestamp;
  private final String name;
  private String attributes = "";
  private static TimeSource timeSource = SystemTimeSource.INSTANCE;

  public OtelSpanEvent(String name, io.opentelemetry.api.common.Attributes attributes) {
    this.name = name;
    this.attributes = AttributesJSONParser.toJson(attributes);
    this.timestamp = getNanosFromTimeSource();
  }

  public OtelSpanEvent(
      String name,
      io.opentelemetry.api.common.Attributes attributes,
      long timestamp,
      TimeUnit unit) {
    this.name = name;
    this.attributes = AttributesJSONParser.toJson(attributes);
    this.timestamp = convertNano(timestamp, unit);
  }

  // JSONParser is a helper class for JSON-encoding OtelSpanEvent attributes
  public static class AttributesJSONParser {
    public static String toJson(io.opentelemetry.api.common.Attributes attributes) {
      if (attributes == null || attributes.isEmpty()) {
        return "";
      }
      StringBuilder jsonBuilder = new StringBuilder();
      jsonBuilder.append('{');

      Set<Map.Entry<AttributeKey<?>, Object>> entrySet = attributes.asMap().entrySet();
      int entryCount = 0;

      for (Map.Entry<AttributeKey<?>, Object> entry : entrySet) {
        if (entryCount > 0) {
          jsonBuilder.append(",");
        }

        String key =
            entry
                .getKey()
                .getKey(); // AttributeKey type has method `getKey()` that "stringifies" the key
        Object value = entry.getValue();

        // Escape key and append it
        jsonBuilder.append("\"").append(escapeJson(key)).append("\":");

        // Append value to jsonBuilder
        appendValue(value, jsonBuilder);
        entryCount++;
      }
      jsonBuilder.append('}');
      return jsonBuilder.toString();
    }
    /**
     * appendValue recursively adds the value of an Attribute to the active StringBuilder in JSON
     * format, depending on the value's type
     *
     * @param value the value to append
     * @param jsonBuilder the active StringBuilder
     */
    private static void appendValue(Object value, StringBuilder jsonBuilder) {
      // Append value based on its type
      if (value instanceof String) {
        jsonBuilder.append("\"").append(escapeJson((String) value)).append("\"");
      } else if (value instanceof List) {
        jsonBuilder.append("[");
        List<?> valArray = (List<?>) value;
        for (int i = 0; i < valArray.size(); i++) {
          if (i > 0) {
            jsonBuilder.append(",");
          }
          appendValue(valArray.get(i), jsonBuilder);
        }
        jsonBuilder.append("]");
      } else if (value instanceof Number || value instanceof Boolean) {
        jsonBuilder.append(value);
      } else {
        jsonBuilder.append("null"); // null for unsupported types
      }
    }

    private static String escapeJson(String value) {
      return value
          .replace("\\", "\\\\")
          .replace("\"", "\\\"")
          .replace("\b", "\\b")
          .replace("\f", "\\f")
          .replace("\n", "\\n")
          .replace("\r", "\\r")
          .replace("\t", "\\t");
    }
  }

  private static long convertNano(long timestamp, TimeUnit unit) {
    return TimeUnit.NANOSECONDS.convert(timestamp, unit);
  }

  private long getNanosFromTimeSource() {
    return timeSource.getCurrentTimeNanos();
  }

  public static void setTimeSource(TimeSource newTimeSource) {
    timeSource = newTimeSource;
  }

  public String toString() {
    StringBuilder builder =
        new StringBuilder(
            "{\"time_unix_nano\":" + this.timestamp + ",\"name\":\"" + this.name + "\"");
    if (!this.attributes.isEmpty()) {
      builder.append(",\"attributes\":").append(this.attributes);
    }
    return builder.append('}').toString();
  }

  @NonNull
  public static String toTag(List<OtelSpanEvent> events) {
    StringBuilder builder = new StringBuilder("[");
    for (int i = 0; i < events.size(); i++) {
      if (i > 0) {
        builder.append(',');
      }
      builder.append(events.get(i).toString());
    }
    return builder.append(']').toString();
  }

  // map of default attribute keys to apply to all SpanEvents generated with recordException, along
  // with the logic to generate the expected value from the provided Throwable
  static final Map<String, Function<Throwable, String>> defaultExceptionAttributes =
      new HashMap<String, Function<Throwable, String>>() {
        {
          put("exception.message", exception -> exception.getMessage());
          put("exception.type", exception -> exception.getClass().getName());
          put(
              "exception.stacktrace",
              exception -> {
                final StringWriter errorString = new StringWriter();
                exception.printStackTrace(new PrintWriter(errorString));
                return errorString.toString();
              });
        }
      };
}

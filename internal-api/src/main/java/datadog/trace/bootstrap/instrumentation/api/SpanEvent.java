package datadog.trace.bootstrap.instrumentation.api;

import datadog.trace.api.time.SystemTimeSource;
import datadog.trace.api.time.TimeSource;
import edu.umd.cs.findbugs.annotations.NonNull;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class SpanEvent implements AgentSpanEvent {

  // TODO TimeSource instance is not retrieved from CoreTracer
  private static TimeSource timeSource = SystemTimeSource.INSTANCE;

  private final String name;
  private final String attributes;
  /** Event timestamp in nanoseconds. */
  private final long timestamp;

  public SpanEvent(String name, AgentSpan.Attributes attributes) {
    this.name = name;
    this.attributes = AttributesJsonParser.toJson(attributes);
    this.timestamp = SpanEvent.timeSource.getCurrentTimeNanos();
  }

  public SpanEvent(String name, AgentSpan.Attributes attributes, long timestamp, TimeUnit unit) {
    this.name = name;
    this.attributes = AttributesJsonParser.toJson(attributes);
    this.timestamp = unit.toNanos(timestamp);
  }

  @NonNull
  public static String toTag(List<AgentSpanEvent> events) {
    StringBuilder builder = new StringBuilder("[");
    for (AgentSpanEvent event : events) {
      if (builder.length() > 1) {
        builder.append(',');
      }
      builder.append(toJson(event));
    }
    return builder.append(']').toString();
  }

  public static String toJson(AgentSpanEvent event) {
    StringBuilder builder =
        new StringBuilder(
            "{\"time_unix_nano\":" + event.timestamp() + ",\"name\":\"" + event.name() + "\"");
    if (!event.attributes().isEmpty()) {
      builder.append(",\"attributes\":").append(event.attributes());
    }
    return builder.append('}').toString();
  }

  @Override
  public long timestamp() {
    return timestamp;
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public String attributes() {
    return attributes;
  }

  /** Helper class for JSON-encoding {@link SpanEvent} {@link #attributes}. */
  public static class AttributesJsonParser {
    public static String toJson(AgentSpan.Attributes attributes) {
      if (attributes == null || attributes.isEmpty()) {
        return "";
      }
      StringBuilder jsonBuilder = new StringBuilder();
      jsonBuilder.append('{');

      Set<Map.Entry<String, String>> entrySet = attributes.asMap().entrySet();

      for (Map.Entry<String, String> entry : entrySet) {
        if (jsonBuilder.length() > 1) {
          jsonBuilder.append(',');
        }
        // XXX not any more
        // AttributeKey type has method `getKey()` that "stringifies" the key
        String key = entry.getKey();
        Object value = entry.getValue();
        // Escape key and append it
        jsonBuilder.append('"').append(escapeJson(key)).append("\":");
        // Append value to jsonBuilder
        appendValue(value, jsonBuilder);
      }
      jsonBuilder.append('}');
      return jsonBuilder.toString();
    }

    /**
     * Recursively adds the value of an {@link SpanAttributes} to the active StringBuilder in JSON
     * format, depending on the value's type.
     *
     * @param value       The value to append
     * @param jsonBuilder The active {@link StringBuilder}
     */
    private static void appendValue(Object value, StringBuilder jsonBuilder) {
      // Append value based on its type
      if (value instanceof String) {
        jsonBuilder.append('"').append(escapeJson((String) value)).append('"');
      } else if (value instanceof List) {
        jsonBuilder.append('[');
        List<?> valArray = (List<?>) value;
        for (int i = 0; i < valArray.size(); i++) {
          if (i > 0) {
            jsonBuilder.append(',');
          }
          appendValue(valArray.get(i), jsonBuilder);
        }
        jsonBuilder.append(']');
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
}

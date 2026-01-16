package datadog.opentelemetry.shim.trace;

import datadog.trace.api.time.SystemTimeSource;
import datadog.trace.api.time.TimeSource;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class OtelSpanEvent {
  public static final String EXCEPTION_SPAN_EVENT_NAME = "exception";
  public static final AttributeKey<String> EXCEPTION_MESSAGE_ATTRIBUTE_KEY =
      AttributeKey.stringKey("exception.message");
  public static final AttributeKey<String> EXCEPTION_TYPE_ATTRIBUTE_KEY =
      AttributeKey.stringKey("exception.type");
  public static final AttributeKey<String> EXCEPTION_STACK_TRACE_ATTRIBUTE_KEY =
      AttributeKey.stringKey("exception.stacktrace");

  // TODO TimeSource instance is not retrieved from CoreTracer
  private static TimeSource timeSource = SystemTimeSource.INSTANCE;

  private final String name;
  private final String attributes;

  /** Event timestamp in nanoseconds. */
  private final long timestamp;

  public OtelSpanEvent(String name, Attributes attributes) {
    this.name = name;
    this.attributes = AttributesJsonParser.toJson(attributes);
    this.timestamp = OtelSpanEvent.timeSource.getCurrentTimeNanos();
  }

  public OtelSpanEvent(String name, Attributes attributes, long timestamp, TimeUnit unit) {
    this.name = name;
    this.attributes = AttributesJsonParser.toJson(attributes);
    this.timestamp = unit.toNanos(timestamp);
  }

  @NonNull
  public static String toTag(List<OtelSpanEvent> events) {
    StringBuilder builder = new StringBuilder("[");
    for (OtelSpanEvent event : events) {
      if (builder.length() > 1) {
        builder.append(',');
      }
      builder.append(event.toJson());
    }
    return builder.append(']').toString();
  }

  /**
   * Make sure exception related attributes are presents and generates them if needed.
   *
   * <p>All exception span events get the following reserved attributes: {@link
   * #EXCEPTION_MESSAGE_ATTRIBUTE_KEY}, {@link #EXCEPTION_TYPE_ATTRIBUTE_KEY} and {@link
   * #EXCEPTION_STACK_TRACE_ATTRIBUTE_KEY}. If additionalAttributes contains a reserved key, the
   * value in additionalAttributes is used. Else, the value is determined from the provided
   * Throwable.
   *
   * @param exception The Throwable from which to build reserved attributes
   * @param additionalAttributes The user-provided attributes
   * @return An {@link Attributes} collection with exception attributes.
   */
  static Attributes initializeExceptionAttributes(
      Throwable exception, Attributes additionalAttributes) {
    // Create an AttributesBuilder with the additionalAttributes provided
    AttributesBuilder builder = additionalAttributes.toBuilder();
    // Handle exception message
    String value = additionalAttributes.get(EXCEPTION_MESSAGE_ATTRIBUTE_KEY);
    if (value == null) {
      value = exception.getMessage();
      builder.put(EXCEPTION_MESSAGE_ATTRIBUTE_KEY, value);
    }
    // Handle exception type
    value = additionalAttributes.get(EXCEPTION_TYPE_ATTRIBUTE_KEY);
    if (value == null) {
      value = exception.getClass().getName();
      builder.put(EXCEPTION_TYPE_ATTRIBUTE_KEY, value);
    }
    // Handle exception stacktrace
    value = additionalAttributes.get(EXCEPTION_STACK_TRACE_ATTRIBUTE_KEY);
    if (value == null) {
      value = stringifyErrorStack(exception);
      builder.put(EXCEPTION_STACK_TRACE_ATTRIBUTE_KEY, value);
    }
    return builder.build();
  }

  static String stringifyErrorStack(Throwable error) {
    final StringWriter errorString = new StringWriter();
    error.printStackTrace(new PrintWriter(errorString));
    return errorString.toString();
  }

  /** Helper class for JSON-encoding {@link OtelSpanEvent} {@link #attributes}. */
  public static class AttributesJsonParser {
    public static String toJson(Attributes attributes) {
      if (attributes == null || attributes.isEmpty()) {
        return "";
      }
      StringBuilder jsonBuilder = new StringBuilder();
      jsonBuilder.append('{');

      Set<Map.Entry<AttributeKey<?>, Object>> entrySet = attributes.asMap().entrySet();

      for (Map.Entry<AttributeKey<?>, Object> entry : entrySet) {
        if (jsonBuilder.length() > 1) {
          jsonBuilder.append(',');
        }
        // AttributeKey type has method `getKey()` that "stringifies" the key
        String key = entry.getKey().getKey();
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
     * Recursively adds the value of an {@link Attributes} to the active StringBuilder in JSON
     * format, depending on the value's type.
     *
     * @param value The value to append
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

  public static void setTimeSource(TimeSource newTimeSource) {
    timeSource = newTimeSource;
  }

  public String toJson() {
    StringBuilder builder =
        new StringBuilder(
            "{\"time_unix_nano\":" + this.timestamp + ",\"name\":\"" + this.name + "\"");
    if (!this.attributes.isEmpty()) {
      builder.append(",\"attributes\":").append(this.attributes);
    }
    return builder.append('}').toString();
  }

  @Override
  public String toString() {
    return "OtelSpanEvent{timestamp="
        + this.timestamp
        + ", name='"
        + this.name
        + "', attributes='"
        + this.attributes
        + "'}";
  }
}

package datadog.opentelemetry.shim.trace;

import static java.util.Collections.emptyMap;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.Map;

/** Helpers to record OpenTelemetry span events on agent spans. */
public final class OtelSpanEvent {
  public static final String EXCEPTION_SPAN_EVENT_NAME = "exception";
  public static final AttributeKey<String> EXCEPTION_MESSAGE_ATTRIBUTE_KEY =
      AttributeKey.stringKey("exception.message");
  public static final AttributeKey<String> EXCEPTION_TYPE_ATTRIBUTE_KEY =
      AttributeKey.stringKey("exception.type");
  public static final AttributeKey<String> EXCEPTION_STACK_TRACE_ATTRIBUTE_KEY =
      AttributeKey.stringKey("exception.stacktrace");

  private OtelSpanEvent() {}

  /**
   * Converts OpenTelemetry attributes into span event attributes.
   *
   * @param attributes The OpenTelemetry attributes to convert.
   * @return The attributes keyed by attribute name.
   */
  static Map<String, ?> eventAttributes(Attributes attributes) {
    if (attributes == null || attributes.isEmpty()) {
      return emptyMap();
    }
    // Size the map to hold all attributes without resizing given the default load factor
    Map<String, Object> map = new HashMap<>((int) (attributes.size() / 0.75f) + 1);
    attributes.forEach((key, value) -> map.put(key.getKey(), value));
    return map;
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
}

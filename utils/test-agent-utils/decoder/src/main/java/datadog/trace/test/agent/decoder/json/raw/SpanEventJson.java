package datadog.trace.test.agent.decoder.json.raw;

import com.squareup.moshi.Json;
import datadog.trace.test.agent.decoder.DecodedSpanEvent;
import datadog.trace.test.agent.decoder.DecodedSpanEvents;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SpanEventJson decodes a span event from the {@code span_events} field of the JSON trace format
 * the dd-apm-test-agent exposes. The agent fills that field when the tracer submits a {@code v1.0}
 * payload, where events travel as structured span data; on {@code v0.4} / {@code v0.5} they arrive
 * as a meta tag instead, decoded by {@link DecodedSpanEvents}.
 *
 * <p>Unlike the tag, attribute values are typed wrappers, as the agent models them: {@code {"type":
 * 2, "int_value": 42}}.
 */
final class SpanEventJson {
  private static final int STRING_TYPE = 0;
  private static final int BOOLEAN_TYPE = 1;
  private static final int INT_TYPE = 2;
  private static final int DOUBLE_TYPE = 3;
  private static final int ARRAY_TYPE = 4;

  @Json(name = "time_unix_nano")
  Long timeUnixNano;

  String name;

  Map<String, AttributeValueJson> attributes;

  DecodedSpanEvent toDecodedSpanEvent() {
    if (this.name == null || this.timeUnixNano == null) {
      throw new IllegalStateException(
          "JSON span event missing a required field (name, time_unix_nano): " + this);
    }
    Map<String, Object> decoded = new LinkedHashMap<>();
    if (this.attributes != null) {
      for (Map.Entry<String, AttributeValueJson> attribute : this.attributes.entrySet()) {
        decoded.put(attribute.getKey(), toValue(attribute.getValue()));
      }
    }
    return DecodedSpanEvents.event(this.name, this.timeUnixNano, decoded);
  }

  private Object toValue(AttributeValueJson value) {
    if (value == null) {
      throw new IllegalStateException("JSON span event with a null attribute: " + this);
    }
    if (value.type == null) {
      throw new IllegalStateException("JSON span event with an untyped attribute: " + this);
    }
    switch (value.type) {
      case STRING_TYPE:
        return requireValue(value.stringValue);
      case BOOLEAN_TYPE:
        return requireValue(value.boolValue);
      case INT_TYPE:
        return requireValue(value.intValue);
      case DOUBLE_TYPE:
        return requireValue(value.doubleValue);
      case ARRAY_TYPE:
        if (value.arrayValue == null || value.arrayValue.values == null) {
          throw new IllegalStateException("JSON span event with a malformed array: " + this);
        }
        List<Object> values = new ArrayList<>(value.arrayValue.values.size());
        for (AttributeValueJson item : value.arrayValue.values) {
          values.add(toValue(item));
        }
        return values;
      default:
        throw new IllegalStateException(
            "JSON span event with an unknown attribute type " + value.type + ": " + this);
    }
  }

  private Object requireValue(Object value) {
    if (value == null) {
      throw new IllegalStateException(
          "JSON span event with an attribute missing its typed value: " + this);
    }
    return value;
  }

  @Override
  public String toString() {
    return "SpanEventJson{name='"
        + this.name
        + "', timeUnixNano="
        + this.timeUnixNano
        + ", attributes="
        + this.attributes
        + '}';
  }

  /** A typed attribute value, holding the field matching its type. */
  static final class AttributeValueJson {
    Integer type;

    @Json(name = "string_value")
    String stringValue;

    @Json(name = "bool_value")
    Boolean boolValue;

    @Json(name = "int_value")
    Long intValue;

    @Json(name = "double_value")
    Double doubleValue;

    @Json(name = "array_value")
    ArrayValueJson arrayValue;

    @Override
    public String toString() {
      return "AttributeValueJson{type="
          + this.type
          + ", stringValue="
          + this.stringValue
          + ", boolValue="
          + this.boolValue
          + ", intValue="
          + this.intValue
          + ", doubleValue="
          + this.doubleValue
          + ", arrayValue="
          + (this.arrayValue == null ? null : this.arrayValue.values)
          + '}';
    }
  }

  /** The values of an array attribute. */
  static final class ArrayValueJson {
    List<AttributeValueJson> values;
  }
}

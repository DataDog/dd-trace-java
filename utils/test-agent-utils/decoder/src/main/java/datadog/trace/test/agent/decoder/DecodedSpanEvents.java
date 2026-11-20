package datadog.trace.test.agent.decoder;

import static com.squareup.moshi.JsonReader.Token.BEGIN_OBJECT;
import static com.squareup.moshi.JsonReader.Token.END_DOCUMENT;
import static com.squareup.moshi.JsonReader.Token.NULL;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.unmodifiableList;
import static java.util.Collections.unmodifiableMap;

import com.squareup.moshi.JsonDataException;
import com.squareup.moshi.JsonReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import okio.Buffer;

/**
 * This class decodes the span events the tracer serializes into the {@value #SPAN_EVENTS_TAG} meta
 * tag, the only way events reach the agent on the {@code v0.4} / {@code v0.5} / test-agent JSON
 * protocols. On {@code v1.0} events travel as a structured payload field instead, decoded by the
 * {@code v1} span decoder through {@link #event}.
 *
 * <p>JSON loses the attribute value types, so numbers are typed from their text: a number written
 * with a fraction or an exponent decodes to a {@link Double}, any other number to a {@link Long}.
 * This matches how the tracer writes them.
 */
public final class DecodedSpanEvents {
  private static final String SPAN_EVENTS_TAG = "events";

  private DecodedSpanEvents() {}

  /**
   * Decodes the span events from meta tags.
   *
   * @param meta The span meta collection.
   * @return The decoded events, in the order the tracer serialized them; empty if the tag is absent
   *     or holds no event.
   * @throws IllegalStateException If the tag value is not a well-formed span event array.
   */
  public static List<DecodedSpanEvent> fromMeta(Map<String, String> meta) {
    String tagValue = meta.get(SPAN_EVENTS_TAG);
    if (tagValue == null || tagValue.isEmpty()) {
      return emptyList();
    }
    try (Buffer buffer = new Buffer();
        JsonReader reader = JsonReader.of(buffer.writeUtf8(tagValue))) {
      List<DecodedSpanEvent> events = new ArrayList<>();
      if (reader.peek() == NULL) {
        reader.nextNull();
      } else {
        reader.beginArray();
        while (reader.hasNext()) {
          events.add(readEvent(reader, tagValue));
        }
        reader.endArray();
      }
      if (reader.peek() != END_DOCUMENT) {
        throw new JsonDataException("Unexpected trailing data after span events");
      }
      return events.isEmpty() ? emptyList() : unmodifiableList(events);
    } catch (IOException | JsonDataException | NumberFormatException e) {
      throw new IllegalStateException("Failed to parse span events: " + tagValue, e);
    }
  }

  /**
   * Builds an event from already decoded values, for the structured {@code v1.0} payload field.
   *
   * @param name The event name.
   * @param timeUnixNano The event time, in nanoseconds since the Unix epoch.
   * @param attributes The event attributes, {@code null} for none.
   * @return The event.
   */
  public static DecodedSpanEvent event(
      String name, long timeUnixNano, Map<String, Object> attributes) {
    return new SpanEvent(name, timeUnixNano, attributes);
  }

  private static DecodedSpanEvent readEvent(JsonReader reader, String tagValue) throws IOException {
    if (reader.peek() != BEGIN_OBJECT) {
      throw new IllegalStateException("Malformed span events with a non-object event: " + tagValue);
    }
    String name = null;
    Long timeUnixNano = null;
    Map<String, Object> attributes = null;
    reader.beginObject();
    while (reader.hasNext()) {
      switch (reader.nextName()) {
        case "name":
          name = reader.nextString();
          break;
        case "time_unix_nano":
          timeUnixNano = reader.nextLong();
          break;
        case "attributes":
          attributes = readAttributes(reader);
          break;
        default:
          reader.skipValue();
          break;
      }
    }
    reader.endObject();
    if (name == null || timeUnixNano == null) {
      throw new IllegalStateException(
          "Span event missing a required field (name, time_unix_nano): " + tagValue);
    }
    return new SpanEvent(name, timeUnixNano, attributes);
  }

  private static Map<String, Object> readAttributes(JsonReader reader) throws IOException {
    Map<String, Object> attributes = new LinkedHashMap<>();
    reader.beginObject();
    while (reader.hasNext()) {
      attributes.put(reader.nextName(), readValue(reader));
    }
    reader.endObject();
    return attributes;
  }

  private static Object readValue(JsonReader reader) throws IOException {
    switch (reader.peek()) {
      case STRING:
        return reader.nextString();
      case BOOLEAN:
        return reader.nextBoolean();
      case NUMBER:
        return toNumber(reader.nextString());
      case BEGIN_ARRAY:
        List<Object> values = new ArrayList<>();
        reader.beginArray();
        while (reader.hasNext()) {
          values.add(readValue(reader));
        }
        reader.endArray();
        return values;
      default:
        // The tracer writes null for any unsupported value
        reader.skipValue();
        return null;
    }
  }

  private static Number toNumber(String text) {
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '.' || c == 'e' || c == 'E') {
        return Double.parseDouble(text);
      }
    }
    return Long.parseLong(text);
  }

  private static final class SpanEvent implements DecodedSpanEvent {
    private final String name;
    private final long timeUnixNano;
    private final Map<String, Object> attributes;

    private SpanEvent(String name, long timeUnixNano, Map<String, Object> attributes) {
      this.name = name;
      this.timeUnixNano = timeUnixNano;
      this.attributes =
          attributes == null || attributes.isEmpty()
              ? emptyMap()
              : unmodifiableMap(new LinkedHashMap<>(attributes));
    }

    @Override
    public String getName() {
      return this.name;
    }

    @Override
    public long getTimeUnixNano() {
      return this.timeUnixNano;
    }

    @Override
    public Map<String, Object> getAttributes() {
      return this.attributes;
    }

    @Override
    public String toString() {
      return "SpanEvent{name='"
          + this.name
          + "', timeUnixNano="
          + this.timeUnixNano
          + ", attributes="
          + this.attributes
          + '}';
    }
  }
}

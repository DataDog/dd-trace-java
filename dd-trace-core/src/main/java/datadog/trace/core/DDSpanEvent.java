package datadog.trace.core;

import com.squareup.moshi.FromJson;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.JsonReader;
import com.squareup.moshi.JsonWriter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.ToJson;
import datadog.trace.api.time.SystemTimeSource;
import datadog.trace.api.time.TimeSource;
import datadog.trace.bootstrap.instrumentation.api.SpanNativeAttributes;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A Span Event is a non-functional, instantaneous telemetry signal that happens during the
 * execution of a Span. They have a `name`, which creates a semantic separation between events. They
 * also have `attributes`, which are key-value pairs that can contain any arbitrary data about the
 * event. The `attributes` are normally semantically defined based on the `name` of the event. This
 * data model closely follows the OpenTelemetry specification.
 *
 * @see <a
 *     href="https://github.com/open-telemetry/opentelemetry.io/blob/2b007bc89daf60fe72e25a11f7e7d21887faf4ae/content/en/docs/concepts/signals/traces.md#span-events">OpenTelemetry
 *     Span Events</a>
 */
public class DDSpanEvent {
  private static final Logger LOGGER = LoggerFactory.getLogger(DDSpanEvent.class);
  private static final int TAG_MAX_LENGTH = 25_000;
  private static TimeSource timeSource = SystemTimeSource.INSTANCE;
  private final String name;
  private final SpanNativeAttributes attributes;
  private final long timestampNanos;

  public DDSpanEvent(String name, SpanNativeAttributes attributes) {
    this(name, attributes, timeSource.getCurrentTimeNanos());
  }

  public DDSpanEvent(String name, SpanNativeAttributes attributes, long timestamp) {
    this.name = name;
    this.attributes = attributes;
    this.timestampNanos = timestamp;
  }

  /**
   * The name of the span event.
   *
   * @return event name
   */
  public String getName() {
    return name;
  }

  /**
   * The attributes of the span event.
   *
   * @return event attributes
   */
  public SpanNativeAttributes getAttributes() {
    return attributes;
  }

  /**
   * The timestamp of the span event in nanoseconds.
   *
   * @return event timestamp
   */
  public long getTimestampNanos() {
    return timestampNanos;
  }

  public static void setTimeSource(TimeSource source) {
    timeSource = source;
  }

  public String toJson() {
    return getAdapter().toJson(this);
  }

  /**
   * Encode a span event collection into a Span tag value.
   *
   * @param events The span event collection to encode.
   * @return The encoded tag value, {@code null} if no events.
   */
  public static String toTag(List<DDSpanEvent> events) {
    if (events == null || events.isEmpty()) {
      return null;
    }
    // Manually encode as JSON array
    StringBuilder builder = new StringBuilder("[");
    int index = 0;
    while (index < events.size()) {
      String eventAsJson = events.get(index).toJson();
      int arrayCharsNeeded = index == 0 ? 1 : 2; // Closing bracket and comma separator if needed
      if (eventAsJson.length() + builder.length() + arrayCharsNeeded >= TAG_MAX_LENGTH) {
        // Do no more fit inside a span tag, stop adding span events
        break;
      }
      if (index > 0) {
        builder.append(',');
      }
      builder.append(eventAsJson);
      index++;
    }
    // Notify of dropped events
    while (index < events.size()) {
      LOGGER.debug("Span tag full. Dropping span events {}", events.get(index));
      index++;
    }
    return builder.append(']').toString();
  }

  private static JsonAdapter<DDSpanEvent> getAdapter() {
    return AdapterHolder.ADAPTER;
  }

  private static class AdapterHolder {
    static final JsonAdapter<DDSpanEvent> ADAPTER = createAdapter();

    private static JsonAdapter<DDSpanEvent> createAdapter() {
      Moshi moshi = new Moshi.Builder().add(new DDSpanEventAdapter()).build();
      return moshi.adapter(DDSpanEvent.class);
    }
  }

  /** Custom JSON adapter for {@link DDSpanEvent} objects. */
  private static class DDSpanEventAdapter extends JsonAdapter<DDSpanEvent> {
    @FromJson
    @Override
    public DDSpanEvent fromJson(JsonReader reader) throws IOException {
      throw new UnsupportedOperationException("Deserialization is not implemented");
    }

    @ToJson
    @Override
    public void toJson(JsonWriter writer, DDSpanEvent value) throws IOException {
      writer.beginObject();
      writer.name("time_unix_nano").value(value.timestampNanos);
      writer.name("name").value(value.name);

      if (value.attributes != null && !value.attributes.isEmpty()) {
        writer.name("attributes");
        writeAttributes(writer, value.attributes);
      }

      writer.endObject();
    }

    private void writeAttributes(JsonWriter writer, SpanNativeAttributes attributes)
        throws IOException {
      writer.beginObject();
      for (Map.Entry<SpanNativeAttributes.AttributeKey<?>, Object> entry :
          attributes.data().entrySet()) {
        writer.name(entry.getKey().getKey());
        writeValue(writer, entry.getKey(), entry.getValue());
      }
      writer.endObject();
    }

    private void writeValue(
        JsonWriter writer, SpanNativeAttributes.AttributeKey<?> key, Object value)
        throws IOException {
      if (value == null) {
        return;
      }

      switch (key.getType()) {
        case STRING:
          writer.value((String) value);
          break;
        case BOOLEAN:
          writer.value((Boolean) value);
          break;
        case LONG:
          writer.value((Long) value);
          break;
        case DOUBLE:
          writer.value((Double) value);
          break;
        case STRING_ARRAY:
          writer.beginArray();
          for (String item : (List<String>) value) {
            writer.value(item);
          }
          writer.endArray();
          break;
        case BOOLEAN_ARRAY:
          writer.beginArray();
          for (Boolean item : (List<Boolean>) value) {
            writer.value(item);
          }
          writer.endArray();
          break;
        case LONG_ARRAY:
          writer.beginArray();
          for (Long item : (List<Long>) value) {
            writer.value(item);
          }
          writer.endArray();
          break;
        case DOUBLE_ARRAY:
          writer.beginArray();
          for (Double item : (List<Double>) value) {
            writer.value(item);
          }
          writer.endArray();
          break;
        default:
          // Not a valid type
      }
    }
  }
}

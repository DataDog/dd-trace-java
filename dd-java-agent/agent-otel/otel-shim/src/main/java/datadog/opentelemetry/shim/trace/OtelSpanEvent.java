package datadog.opentelemetry.shim.trace;

import static java.util.Objects.requireNonNull;

import datadog.trace.api.time.SystemTimeSource;
import datadog.trace.api.time.TimeSource;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.SpanAttributes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class OtelSpanEvent {

  private final long timestamp;
  private final String name;
  private final AgentSpan.Attributes attributes;
  private static TimeSource timeSource = SystemTimeSource.INSTANCE;

  public OtelSpanEvent(String name, io.opentelemetry.api.common.Attributes attributes) {
    this.name = name;
    this.attributes =
        OtelConventions.convertAttributes(attributes, SpanAttributes.Builder.Format.EVENTS);
    this.timestamp = getNanosFromTimeSource();
  }

  public OtelSpanEvent(
      String name,
      io.opentelemetry.api.common.Attributes attributes,
      long timestamp,
      TimeUnit unit) {
    this.name = name;
    this.attributes =
        OtelConventions.convertAttributes(attributes, SpanAttributes.Builder.Format.EVENTS);
    this.timestamp = convertNano(timestamp, unit);
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
      builder
          .append(",\"attributes\":")
          .append(SpanAttributes.JSONParser.toJson(this.attributes.asMap()));
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

  private static class Attributes implements AgentSpan.Attributes {
    /** Represent an empty attributes. */
    public static final Attributes EMPTY = new Attributes(Collections.emptyMap());

    private final Map<String, Object> attributes;

    protected Attributes(Map<String, Object> attributes) {
      this.attributes = attributes;
    }

    /**
     * Gets a builder of the specified format to create attributes.
     *
     * @return A builder to create attributes.
     */
    //    public static Attributes.Builder builder(Attributes.Builder.Format format) {
    //      return new Attributes.Builder(format);
    //    }
    /**
     * Create attributes from its map representation.
     *
     * @param map A map representing the attributes.
     * @return The related attributes.
     */
    public static Attributes fromMap(Map<String, Object> map) {
      return new Attributes(new HashMap<>(map));
    }

    public Map<String, Object> asMap() {
      return this.attributes;
    }

    public boolean isEmpty() {
      return this.attributes.isEmpty();
    }

    public String toString() {
      return "Attributes{" + this.attributes + '}';
    }

    // JSONParser is a helper class for turning the Map<String,String> that holds the attributes
    // into
    // a JSON string
    public static class JSONParser {
      public static String toJson(Map<String, Object> map) {
        StringBuilder jsonBuilder = new StringBuilder();
        jsonBuilder.append('{');

        Set<Map.Entry<String, Object>> entrySet = map.entrySet();
        int entryCount = 0;

        for (Map.Entry<String, Object> entry : entrySet) {
          if (entryCount > 0) {
            jsonBuilder.append(",");
          }

          String key = entry.getKey();
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
          jsonBuilder.append("null"); // For unsupported types, use null
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

    public static class Builder {
      // SpanLinks and OtelSpanEvents are encoded in different formats; Format represents the
      // encoding
      // standard
      public enum Format {
        LINKS,
        EVENTS
      }

      private final Map<String, Object> attributes;
      private final OtelSpanEvent.Attributes.Builder.Format format;

      protected Builder(OtelSpanEvent.Attributes.Builder.Format format) {
        this.attributes = new HashMap<>();
        this.format = format;
      }

      public OtelSpanEvent.Attributes.Builder put(String key, String value) {
        requireNonNull(key, "key must not be null");
        if (value != null) {
          this.attributes.put(key, value);
        }
        return this;
      }

      public OtelSpanEvent.Attributes.Builder put(String key, boolean value) {
        requireNonNull(key, "key must not be null");
        if (this.format == OtelSpanEvent.Attributes.Builder.Format.LINKS) {
          this.attributes.put(key, Boolean.toString(value));
        } else if (this.format == OtelSpanEvent.Attributes.Builder.Format.EVENTS) {
          this.attributes.put(key, value);
        }
        return this;
      }

      public OtelSpanEvent.Attributes.Builder put(String key, long value) {
        requireNonNull(key, "key must not be null");
        if (this.format == OtelSpanEvent.Attributes.Builder.Format.LINKS) {
          this.attributes.put(key, Long.toString(value));
        } else if (this.format == OtelSpanEvent.Attributes.Builder.Format.EVENTS) {
          this.attributes.put(key, value);
        }
        return this;
      }

      public OtelSpanEvent.Attributes.Builder put(String key, double value) {
        requireNonNull(key, "key must not be null");
        if (this.format == OtelSpanEvent.Attributes.Builder.Format.LINKS) {
          this.attributes.put(key, Double.toString(value));
        } else if (this.format == OtelSpanEvent.Attributes.Builder.Format.EVENTS) {
          this.attributes.put(key, value);
        }
        return this;
      }

      public OtelSpanEvent.Attributes.Builder putStringArray(String key, List<String> array) {
        return putArray(key, array);
      }

      public OtelSpanEvent.Attributes.Builder putBooleanArray(String key, List<Boolean> array) {
        return putArray(key, array);
      }

      public OtelSpanEvent.Attributes.Builder putLongArray(String key, List<Long> array) {
        return putArray(key, array);
      }

      public OtelSpanEvent.Attributes.Builder putDoubleArray(String key, List<Double> array) {
        return putArray(key, array);
      }

      protected <T> OtelSpanEvent.Attributes.Builder putArray(String key, List<T> array) {
        requireNonNull(key, "key must not be null");
        if (array != null) {
          if (this.format == OtelSpanEvent.Attributes.Builder.Format.LINKS) {
            for (int index = 0; index < array.size(); index++) {
              Object value = array.get(index);
              if (value != null) {
                this.attributes.put(key + "." + index, value.toString());
              }
            }
          } else if (this.format == OtelSpanEvent.Attributes.Builder.Format.EVENTS) {
            this.attributes.put(key, array);
          }
        }
        return this;
      }

      public OtelSpanEvent.Attributes build() {
        return new OtelSpanEvent.Attributes(this.attributes);
      }
    }
  }
}

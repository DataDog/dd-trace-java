package datadog.trace.bootstrap.instrumentation.api;

import static java.util.Objects.requireNonNull;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan.Attributes;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** This class is a base implementation of {@link Attributes}. */
public class SpanAttributes implements Attributes {
  /** Represent an empty attributes. */
  public static final Attributes EMPTY = new SpanAttributes(Collections.emptyMap());

  private final Map<String, Object> attributes;

  protected SpanAttributes(Map<String, Object> attributes) {
    this.attributes = attributes;
  }

  /**
   * Gets a builder to create attributes.
   *
   * @return A builder to create attributes.
   */
  public static Builder builder(boolean stringify) {
    return new Builder(stringify);
  }

  /**
   * Create attributes from its map representation.
   *
   * @param map A map representing the attributes.
   * @return The related attributes.
   */
  public static SpanAttributes fromMap(Map<String, Object> map) {
    return new SpanAttributes(new HashMap<>(map));
  }

  @Override
  public Map<String, Object> asMap() {
    return this.attributes;
  }

  @Override
  public boolean isEmpty() {
    return this.attributes.isEmpty();
  }

  @Override
  public String toString() {
    return "SpanAttributes{" + this.attributes + '}';
  }

  // Helper class for turning the Map<String,String> that holds the attributes into a JSON string
  public static class JSONParser {
    public static String toJson(Map<String, Object> map) {
      StringBuilder jsonBuilder = new StringBuilder();
      jsonBuilder.append("{");

      Set<Map.Entry<String, Object>> entrySet = map.entrySet();
      int entryCount = 0;
      int totalEntries = entrySet.size();

      for (Map.Entry<String, Object> entry : entrySet) {
        if (entryCount > 0) {
          jsonBuilder.append(",");
        }

        String key = entry.getKey();
        Object value = entry.getValue();

        // Escape key and append it
        jsonBuilder.append("\"").append(escapeJson(key)).append("\":");

        // Append value based on its type
        if (value instanceof String) {
          jsonBuilder.append("\"").append(escapeJson((String) value)).append("\"");
        } else if (value instanceof Number) {
          jsonBuilder.append(value.toString());
        } else if (value instanceof Boolean) {
          jsonBuilder.append(value.toString());
        } else {
          jsonBuilder.append("null"); // For unsupported types, use null
        }

        entryCount++;
      }

      jsonBuilder.append("}");

      return jsonBuilder.toString();
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
    private final Map<String, Object> attributes;
    private final boolean stringify;

    protected Builder(boolean stringify) {
      this.attributes = new HashMap<>();
      this.stringify = stringify;
    }

    public Builder put(String key, String value) {
      requireNonNull(key, "key must not be null");
      if (value != null) {
        this.attributes.put(key, value);
      }
      return this;
    }

    public Builder put(String key, boolean value) {
      requireNonNull(key, "key must not be null");
      if (this.stringify) {
        this.attributes.put(key, Boolean.toString(value));
      } else {
        this.attributes.put(key, value);
      }
      return this;
    }

    public Builder put(String key, long value) {
      requireNonNull(key, "key must not be null");
      if (this.stringify) {
        this.attributes.put(key, Long.toString(value));
      } else {
        this.attributes.put(key, value);
      }
      return this;
    }

    public Builder put(String key, double value) {
      requireNonNull(key, "key must not be null");
      if (this.stringify) {
        this.attributes.put(key, Double.toString(value));
      } else {
        this.attributes.put(key, value);
      }
      return this;
    }

    public Builder putStringArray(String key, List<String> array) {
      return putArray(key, array);
    }

    public Builder putBooleanArray(String key, List<Boolean> array) {
      return putArray(key, array);
    }

    public Builder putLongArray(String key, List<Long> array) {
      return putArray(key, array);
    }

    public Builder putDoubleArray(String key, List<Double> array) {
      return putArray(key, array);
    }

    protected <T> Builder putArray(String key, List<T> array) {
      requireNonNull(key, "key must not be null");
      if (array != null) {
        for (int index = 0; index < array.size(); index++) {
          Object value = array.get(index);
          if (value != null) {
            if (this.stringify) {
              this.attributes.put(key + "." + index, value.toString());
            } else {
              this.attributes.put(key + "." + index, value);
            }
          }
        }
      }
      return this;
    }

    public Attributes build() {
      return new SpanAttributes(this.attributes);
    }
  }
}

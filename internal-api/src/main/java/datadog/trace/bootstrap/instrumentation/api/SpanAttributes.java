package datadog.trace.bootstrap.instrumentation.api;

import static java.util.Objects.requireNonNull;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan.Attributes;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** This class is a base implementation of {@link Attributes}. */
public class SpanAttributes implements Attributes {
  /** Represent an empty attributes. */
  public static final Attributes EMPTY = new SpanAttributes(Collections.emptyMap());

  private final Map<String, String> attributes;

  protected SpanAttributes(Map<String, String> attributes) {
    this.attributes = attributes;
  }

  /**
   * Gets a builder to create attributes.
   *
   * @return A builder to create attributes.
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Create attributes from its map representation.
   *
   * @param map A map representing the attributes.
   * @return The related attributes.
   */
  public static SpanAttributes fromMap(Map<String, String> map) {
    return new SpanAttributes(new HashMap<>(map));
  }

  @Override
  public Map<String, String> asMap() {
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

  public static class Builder {
    private final Map<String, String> attributes;

    protected Builder() {
      this.attributes = new HashMap<>();
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
      this.attributes.put(key, Boolean.toString(value));
      return this;
    }

    public Builder put(String key, long value) {
      requireNonNull(key, "key must not be null");
      this.attributes.put(key, Long.toString(value));
      return this;
    }

    public Builder put(String key, double value) {
      requireNonNull(key, "key must not be null");
      this.attributes.put(key, Double.toString(value));
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
            this.attributes.put(key + "." + index, value.toString());
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

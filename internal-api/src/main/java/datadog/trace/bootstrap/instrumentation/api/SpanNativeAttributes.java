package datadog.trace.bootstrap.instrumentation.api;

import static datadog.trace.bootstrap.instrumentation.api.SpanNativeAttributes.AttributeKey.booleanArrayKey;
import static datadog.trace.bootstrap.instrumentation.api.SpanNativeAttributes.AttributeKey.booleanKey;
import static datadog.trace.bootstrap.instrumentation.api.SpanNativeAttributes.AttributeKey.doubleArrayKey;
import static datadog.trace.bootstrap.instrumentation.api.SpanNativeAttributes.AttributeKey.doubleKey;
import static datadog.trace.bootstrap.instrumentation.api.SpanNativeAttributes.AttributeKey.longArrayKey;
import static datadog.trace.bootstrap.instrumentation.api.SpanNativeAttributes.AttributeKey.longKey;
import static datadog.trace.bootstrap.instrumentation.api.SpanNativeAttributes.AttributeKey.stringArrayKey;
import static datadog.trace.bootstrap.instrumentation.api.SpanNativeAttributes.AttributeKey.stringKey;
import static java.util.Objects.requireNonNull;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Represents span attributes (used by {@link SpanLink} and {@link DDSpanEvent}). Unlike {@link
 * SpanAttributes}, this class supports all valid types for attribute values, while {@link
 * SpanAttributes} stringify all values. The data structure is modeled closely after the `Attribute`
 * in OpenTelemetry.
 *
 * @see <a
 *     href="https://github.com/open-telemetry/opentelemetry-specification/blob/3c50530eca10c95f01aaa232bf558e6c6b697e9d/specification/common/README.md#attribute">OpenTelemetry
 *     Attribute</a>
 */
public class SpanNativeAttributes {
  public static final SpanNativeAttributes EMPTY = new SpanNativeAttributes(Collections.emptyMap());

  private final Map<AttributeKey<?>, Object> attributes;

  /* Use the {#builder} to create instances of this class */
  public SpanNativeAttributes(Map<AttributeKey<?>, Object> attributes) {
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
   * Returns the complete set of attributes.
   *
   * @return The attributes map.
   */
  public Map<AttributeKey<?>, Object> data() {
    return attributes;
  }

  public boolean isEmpty() {
    return this.attributes.isEmpty();
  }

  @Override
  public String toString() {
    return "SpanNativeAttributes{" + this.attributes + '}';
  }

  @Override
  public boolean equals(Object o) {
    if (o == this) {
      return true;
    }
    if (o instanceof SpanNativeAttributes) {
      SpanNativeAttributes that = (SpanNativeAttributes) o;
      return this.attributes.equals(that.data());
    }
    return false;
  }

  /** Builder to ensure type-safe attribute creation. */
  public static class Builder {
    private final Map<AttributeKey<?>, Object> attributes;

    protected Builder() {
      this.attributes = new LinkedHashMap<>();
    }

    /**
     * Adds an attribute key and non-null value. If the value is null, the attribute will be
     * ignored.
     *
     * @param key a non-null attribute name
     * @param value a non-null attribute value
     * @return this builder
     */
    public Builder put(String key, String value) {
      requireNonNull(key, "key must not be null");
      if (value != null) {
        this.attributes.put(stringKey(key), value);
      }
      return this;
    }

    /**
     * Adds an attribute key and non-null value. If the value is null, the attribute will be
     * ignored.
     *
     * @param key a non-null attribute name
     * @param value a non-null attribute value
     * @return this builder
     */
    public Builder put(String key, Boolean value) {
      requireNonNull(key, "key must not be null");
      if (value != null) {
        this.attributes.put(booleanKey(key), value);
      }
      return this;
    }

    /**
     * Adds an attribute key and non-null value. If the value is null, the attribute will be
     * ignored.
     *
     * @param key a non-null attribute name
     * @param value a non-null attribute value
     * @return this builder
     */
    public Builder put(String key, Long value) {
      requireNonNull(key, "key must not be null");
      if (value != null) {
        this.attributes.put(longKey(key), value);
      }
      return this;
    }

    /**
     * Adds an attribute key and non-null value. If the value is null, the attribute will be
     * ignored.
     *
     * @param key a non-null attribute name
     * @param value a non-null attribute value
     * @return this builder
     */
    public Builder put(String key, Double value) {
      requireNonNull(key, "key must not be null");
      if (value != null) {
        this.attributes.put(doubleKey(key), value);
      }
      return this;
    }

    /**
     * Adds an attribute key and non-null, homogeneous array of non-null values. If the array is
     * null, the attribute will be ignored.
     *
     * @param key a non-null attribute name
     * @param array a non-null array of non-null values
     * @return this builder
     */
    public Builder putStringArray(String key, List<String> array) {
      requireNonNull(key, "key must not be null");
      if (array != null) {
        this.attributes.put(stringArrayKey(key), array);
      }
      return this;
    }

    /**
     * Adds an attribute key and non-null, homogeneous array of non-null values. If the array is
     * null, the attribute will be ignored.
     *
     * @param key a non-null attribute name
     * @param array a non-null array of non-null values
     * @return this builder
     */
    public Builder putBooleanArray(String key, List<Boolean> array) {
      requireNonNull(key, "key must not be null");
      if (array != null) {
        this.attributes.put(booleanArrayKey(key), array);
      }
      return this;
    }

    /**
     * Adds an attribute key and non-null, homogeneous array of non-null values. If the array is
     * null, the attribute will be ignored.
     *
     * @param key a non-null attribute name
     * @param array a non-null array of non-null values
     * @return this builder
     */
    public Builder putLongArray(String key, List<Long> array) {
      requireNonNull(key, "key must not be null");
      if (array != null) {
        this.attributes.put(longArrayKey(key), array);
      }
      return this;
    }

    /**
     * Adds an attribute key and non-null, homogeneous array of non-null values.
     *
     * @param key a non-null attribute name
     * @param array a non-null array of non-null values
     * @return this builder
     */
    public Builder putDoubleArray(String key, List<Double> array) {
      requireNonNull(key, "key must not be null");
      if (array != null) {
        this.attributes.put(doubleArrayKey(key), array);
      }
      return this;
    }

    public SpanNativeAttributes build() {
      return new SpanNativeAttributes(this.attributes);
    }
  }

  /** All valid types for attribute values. */
  public enum AttributeType {
    STRING, // String
    BOOLEAN, // Boolean
    LONG, // Long
    DOUBLE, // Double
    STRING_ARRAY, // List<String>
    BOOLEAN_ARRAY, // List<Boolean>
    LONG_ARRAY, // List<Long>
    DOUBLE_ARRAY // List<Double>
  }

  /**
   * Represents an attribute key. Can be cached and reused to prevent object creation on every
   * attribute set.
   *
   * @param <T> the respective types of {@link AttributeType}
   */
  public interface AttributeKey<T> extends Comparable<AttributeKey<?>> {
    /**
     * The non-null, unique key name.
     *
     * @return the key name
     */
    String getKey();

    /**
     * The type of the attribute value.
     *
     * @return the type
     */
    AttributeType getType();

    static AttributeKey<String> stringKey(String key) {
      return create(key, AttributeType.STRING);
    }

    static AttributeKey<Boolean> booleanKey(String key) {
      return create(key, AttributeType.BOOLEAN);
    }

    static AttributeKey<Long> longKey(String key) {
      return create(key, AttributeType.LONG);
    }

    static AttributeKey<Double> doubleKey(String key) {
      return create(key, AttributeType.DOUBLE);
    }

    static AttributeKey<List<String>> stringArrayKey(String key) {
      return create(key, AttributeType.STRING_ARRAY);
    }

    static AttributeKey<List<Boolean>> booleanArrayKey(String key) {
      return create(key, AttributeType.BOOLEAN_ARRAY);
    }

    static AttributeKey<List<Long>> longArrayKey(String key) {
      return create(key, AttributeType.LONG_ARRAY);
    }

    static AttributeKey<List<Double>> doubleArrayKey(String key) {
      return create(key, AttributeType.DOUBLE_ARRAY);
    }

    /** Internal method to create an {@link AttributeKey}. */
    static <T> AttributeKey<T> create(String key, AttributeType type) {
      return new AttributeKeyImpl<>(type, key);
    }

    /** Keys are unique based on their key names only, regardless of the type. */
    @Override
    default int compareTo(AttributeKey<?> o) {
      return getKey().compareTo(o.getKey());
    }

    class AttributeKeyImpl<T> implements AttributeKey<T> {
      private final AttributeType type;
      private final String key;
      private final int hashCode;

      private AttributeKeyImpl(AttributeType type, String key) {
        this.type = type;
        this.key = key;

        // Two AttributeKeyImpl are equal if they have the same key, regardless of the type
        this.hashCode = key.hashCode();
      }

      @Override
      public String getKey() {
        return key;
      }

      @Override
      public AttributeType getType() {
        return type;
      }

      // Two AttributeKeyImpl are equal if they have the same key, regardless of the type
      @Override
      public boolean equals(Object o) {
        if (o == this) {
          return true;
        }
        if (o instanceof AttributeKeyImpl) {
          AttributeKeyImpl<?> that = (AttributeKeyImpl<?>) o;
          return getKey().equals(that.getKey());
        }
        return false;
      }

      @Override
      public int hashCode() {
        return hashCode;
      }

      @Override
      public String toString() {
        return key;
      }
    }
  }
}

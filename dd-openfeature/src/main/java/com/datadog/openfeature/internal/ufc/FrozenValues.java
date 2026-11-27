package com.datadog.openfeature.internal.ufc;

import dev.openfeature.sdk.MutableStructure;
import dev.openfeature.sdk.Structure;
import dev.openfeature.sdk.Value;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the OpenFeature {@link Value} of a JSON variant once, when the configuration is parsed, so
 * that each evaluation returns the same instance instead of a new deep copy.
 *
 * <p>Each JSON object becomes a {@link FrozenStructure}: a {@link MutableStructure} over an
 * unmodifiable map, so {@code getValue} returns the shared child without a copy and {@code add}
 * throws {@link UnsupportedOperationException}. It keeps the equality of {@link
 * Value#objectToValue}. Each JSON array becomes an unmodifiable list.
 */
final class FrozenValues {
  /**
   * Tells whether the loaded OpenFeature SDK supports {@link Long} values. 1.22.0 added {@code
   * Value(Long)}, and 1.22.1 taught {@link Structure#convertValue} to convert it back.
   */
  static final boolean LONG_VALUES = hasLongValues();

  private static final Value NULL = new Value();
  private static final Value TRUE = new Value(true);
  private static final Value FALSE = new Value(false);

  private FrozenValues() {}

  private static boolean hasLongValues() {
    try {
      // Not new Value(1L): that constructor is missing before 1.22.0.
      return Long.valueOf(1L).equals(new MutableStructure().convertValue(Value.objectToValue(1L)));
    } catch (final RuntimeException e) {
      // Before 1.22.0 objectToValue rejects a Long; 1.22.0 cannot convert it back.
      return false;
    }
  }

  /** Tells whether the value is a JSON object or array, the values that are frozen. */
  static boolean isContainer(final Object value) {
    return value instanceof Map || value instanceof List;
  }

  /**
   * Converts a parsed JSON tree to a {@link Value} that cannot change. The result is equal to
   * {@code Value.objectToValue(tree)}.
   */
  static Value freeze(final Object tree) {
    if (tree instanceof Map) {
      final Map<?, ?> source = (Map<?, ?>) tree;
      final Map<String, Value> attributes = new HashMap<>(capacity(source.size()));
      for (final Map.Entry<?, ?> entry : source.entrySet()) {
        attributes.put((String) entry.getKey(), freeze(entry.getValue()));
      }
      return new Value(new FrozenStructure(Collections.unmodifiableMap(attributes)));
    }
    if (tree instanceof List) {
      final List<?> source = (List<?>) tree;
      final List<Value> items = new ArrayList<>(source.size());
      for (final Object element : source) {
        items.add(freeze(element));
      }
      return new Value(Collections.unmodifiableList(items));
    }
    if (tree == null) {
      return NULL;
    }
    if (tree instanceof Boolean) {
      // Values cannot change, so the common scalars are shared.
      return (Boolean) tree ? TRUE : FALSE;
    }
    return Value.objectToValue(tree);
  }

  /**
   * The capacity of a hash map that holds the given number of entries without resizing. It is at
   * least the default 16, so the map has the same table size, and so the same key order, as the map
   * that {@link Value#objectToValue} and {@link Structure#asObjectMap} build.
   */
  private static int capacity(final int size) {
    return (int) Math.max(16, Math.min(Integer.MAX_VALUE, ((long) size * 4 + 2) / 3));
  }

  /**
   * A structure that cannot change. It also keeps its plain-map form, because applications often
   * read a JSON flag with {@link #asObjectMap()}.
   */
  static final class FrozenStructure extends MutableStructure {
    private volatile Map<String, Object> objectMap;

    FrozenStructure(final Map<String, Value> attributes) {
      super(attributes);
    }

    @Override
    public MutableStructure add(final String key, final Value value) {
      throw readOnly();
    }

    @Override
    public MutableStructure add(final String key, final Boolean value) {
      throw readOnly();
    }

    @Override
    public MutableStructure add(final String key, final String value) {
      throw readOnly();
    }

    @Override
    public MutableStructure add(final String key, final Integer value) {
      throw readOnly();
    }

    @Override
    public MutableStructure add(final String key, final Double value) {
      throw readOnly();
    }

    @Override
    public MutableStructure add(final String key, final Instant value) {
      throw readOnly();
    }

    @Override
    public MutableStructure add(final String key, final Structure value) {
      throw readOnly();
    }

    @Override
    public MutableStructure add(final String key, final List<Value> value) {
      throw readOnly();
    }

    private static UnsupportedOperationException readOnly() {
      return new UnsupportedOperationException(
          "JSON flag values are shared and read-only: copy the value before you change it");
    }

    /**
     * Returns the plain-map form, built on the first call and then shared. The map and its nested
     * maps and lists cannot change.
     */
    @Override
    public Map<String, Object> asObjectMap() {
      Map<String, Object> map = this.objectMap;
      if (map == null) {
        map = toObjectMap(this);
        // A race builds equal immutable maps; any of them can be kept.
        this.objectMap = map;
      }
      return map;
    }

    private static Map<String, Object> toObjectMap(final Structure structure) {
      final Map<String, Value> attributes = structure.asUnmodifiableMap();
      final Map<String, Object> map = new HashMap<>(capacity(attributes.size()));
      attributes.forEach((key, value) -> map.put(key, toObject(value)));
      return Collections.unmodifiableMap(map);
    }

    /**
     * The same conversion as {@link Structure#convertValue}, with unmodifiable containers. {@link
     * #freeze} gives each child a non-null {@link Value}, and each nested structure is frozen.
     */
    private static Object toObject(final Value value) {
      if (value.isStructure()) {
        return value.asStructure().asObjectMap();
      }
      if (value.isList()) {
        final List<Value> source = value.asList();
        final List<Object> items = new ArrayList<>(source.size());
        for (final Value item : source) {
          items.add(toObject(item));
        }
        return Collections.unmodifiableList(items);
      }
      return value.asObject();
    }
  }
}

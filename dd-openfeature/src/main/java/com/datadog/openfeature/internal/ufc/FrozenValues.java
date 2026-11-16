package com.datadog.openfeature.internal.ufc;

import dev.openfeature.sdk.MutableStructure;
import dev.openfeature.sdk.Structure;
import dev.openfeature.sdk.Value;
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
 * throws {@link UnsupportedOperationException}. The type is the one {@link Value#objectToValue}
 * returns, so {@code equals} and {@code hashCode} are unchanged. Each JSON array becomes an
 * unmodifiable list. A {@link Value} has no setters, so the whole tree cannot change.
 */
public final class FrozenValues {
  private FrozenValues() {}

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
    return Value.objectToValue(tree);
  }

  private static int capacity(final int size) {
    return (int) Math.min(Integer.MAX_VALUE, (long) size * 4 / 3 + 1);
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
      for (final Map.Entry<String, Value> entry : attributes.entrySet()) {
        map.put(entry.getKey(), toObject(entry.getValue()));
      }
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

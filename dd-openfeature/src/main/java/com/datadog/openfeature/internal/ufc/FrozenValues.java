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
   * Freezes the JSON variants of one document, sharing every part they have in common: each
   * distinct string once, each distinct scalar {@link Value} once, and each distinct object or
   * array once. The values cannot change, so sharing is invisible to readers.
   *
   * <p>Parsing runs on a background thread, so the table costs nothing on the evaluation path, and
   * it is dropped with the freezer: only the shared values are retained. Children are frozen first,
   * so two containers are equal exactly when they hold the same keys and the very same child
   * instances, and each lookup compares one level by identity.
   */
  static final class Freezer {
    private final Map<Object, Object> shared = new HashMap<>();

    Value freeze(final Object tree) {
      if (tree instanceof Map) {
        final Map<?, ?> source = (Map<?, ?>) tree;
        final Map<String, Value> attributes = new HashMap<>(capacity(source.size()));
        for (final Map.Entry<?, ?> entry : source.entrySet()) {
          attributes.put(string((String) entry.getKey()), freeze(entry.getValue()));
        }
        return shared(new StructureKey(attributes));
      }
      if (tree instanceof List) {
        final List<?> source = (List<?>) tree;
        final List<Value> items = new ArrayList<>(source.size());
        for (final Object element : source) {
          items.add(freeze(element));
        }
        return shared(new ListKey(items));
      }
      if (tree == null) {
        return NULL;
      }
      if (tree instanceof Boolean) {
        return (Boolean) tree ? TRUE : FALSE;
      }
      // Integer 1, Long 1 and Double 1.0 are different keys, as they are different values.
      final Object scalar = tree instanceof String ? string((String) tree) : tree;
      Object value = shared.get(new ScalarKey(scalar));
      if (value == null) {
        value = Value.objectToValue(scalar);
        shared.put(new ScalarKey(scalar), value);
      }
      return (Value) value;
    }

    private String string(final String s) {
      final Object existing = shared.putIfAbsent(s, s);
      return existing == null ? s : (String) existing;
    }

    private Value shared(final ContainerKey key) {
      final Object existing = shared.get(key);
      if (existing != null) {
        return (Value) existing;
      }
      final Value value = key.toValue();
      shared.put(key, value);
      return value;
    }
  }

  /** A scalar as a table key, so it can't collide with an interned string of the same text. */
  static final class ScalarKey {
    private final Object scalar;

    ScalarKey(final Object scalar) {
      this.scalar = scalar;
    }

    @Override
    public boolean equals(final Object o) {
      return o instanceof ScalarKey
          && scalar.getClass() == ((ScalarKey) o).scalar.getClass()
          && scalar.equals(((ScalarKey) o).scalar);
    }

    @Override
    public int hashCode() {
      return scalar.hashCode() * 31 + scalar.getClass().hashCode();
    }
  }

  /** A container whose children are already shared, compared one level deep by identity. */
  abstract static class ContainerKey {
    abstract Value toValue();
  }

  static final class StructureKey extends ContainerKey {
    private final Map<String, Value> attributes;
    private final int hash;

    StructureKey(final Map<String, Value> attributes) {
      this.attributes = attributes;
      int hash = 0;
      for (final Map.Entry<String, Value> entry : attributes.entrySet()) {
        hash += entry.getKey().hashCode() ^ System.identityHashCode(entry.getValue());
      }
      this.hash = hash;
    }

    @Override
    Value toValue() {
      return new Value(new FrozenStructure(Collections.unmodifiableMap(attributes)));
    }

    @Override
    public boolean equals(final Object o) {
      if (!(o instanceof StructureKey)) {
        return false;
      }
      final StructureKey other = (StructureKey) o;
      // HashMap only calls equals for keys whose hashes already matched.
      if (attributes.size() != other.attributes.size()) {
        return false;
      }
      for (final Map.Entry<String, Value> entry : attributes.entrySet()) {
        if (other.attributes.get(entry.getKey()) != entry.getValue()) {
          return false;
        }
      }
      return true;
    }

    @Override
    public int hashCode() {
      return hash;
    }
  }

  static final class ListKey extends ContainerKey {
    private final List<Value> items;
    private final int hash;

    ListKey(final List<Value> items) {
      this.items = items;
      int hash = 1;
      for (final Value item : items) {
        hash = 31 * hash + System.identityHashCode(item);
      }
      this.hash = hash;
    }

    @Override
    Value toValue() {
      return new Value(Collections.unmodifiableList(items));
    }

    @Override
    public boolean equals(final Object o) {
      if (!(o instanceof ListKey)) {
        return false;
      }
      final ListKey other = (ListKey) o;
      if (items.size() != other.items.size()) {
        return false;
      }
      for (int i = 0; i < items.size(); i++) {
        if (items.get(i) != other.items.get(i)) {
          return false;
        }
      }
      return true;
    }

    @Override
    public int hashCode() {
      return hash;
    }
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

package com.datadog.openfeature.internal.ufc;

import dev.openfeature.sdk.Value;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * A read-only map of a frozen JSON object's attributes, held in flat arrays instead of one node per
 * entry.
 *
 * <p>Entries keep the iteration order of the map they were copied from. A small map is searched by
 * a linear scan, with no hashing; a larger one adds an open-addressed index into the arrays. Keys
 * that the freezer shared match on {@link String#equals}'s identity check. {@code get(null)}
 * misses, as with {@link java.util.HashMap}.
 *
 * <p>The views ({@link #entrySet}, {@link #keySet}, {@link #values}) are created on each call and
 * never cached: the maps are retained for as long as the configuration, and a cached view would
 * stay with each of them.
 */
final class FrozenMap extends AbstractMap<String, Value> {
  /** Up to this many entries, a lookup scans the keys instead of hashing. */
  static final int MAX_SCANNED = 8;

  private final String[] keys;
  private final Value[] values;
  // Slots hold an entry's position plus one; zero is empty. Null for a scanned map.
  private final int[] index;

  private FrozenMap(final String[] keys, final Value[] values, final int[] index) {
    this.keys = keys;
    this.values = values;
    this.index = index;
  }

  /** Copies the entries of {@code source}, in its iteration order. */
  static FrozenMap copyOf(final Map<String, Value> source) {
    final int size = source.size();
    final String[] keys = new String[size];
    final Value[] values = new Value[size];
    int i = 0;
    for (final Map.Entry<String, Value> entry : source.entrySet()) {
      keys[i] = entry.getKey();
      values[i] = entry.getValue();
      i++;
    }
    return new FrozenMap(keys, values, size > MAX_SCANNED ? index(keys) : null);
  }

  /**
   * Builds the map with its entries in the order a {@link java.util.HashMap} of the given initial
   * capacity would iterate them, had they been put in array order, without building that map.
   * Returns {@code null} when a bucket would hold enough entries for the HashMap to restructure it,
   * since the order then depends on that; the caller falls back to a HashMap.
   */
  static FrozenMap inHashMapOrder(
      final String[] keys, final Value[] values, final int initialCapacity) {
    final int size = keys.length;
    // HashMap's table: the power of two at or above the initial capacity, which the callers size
    // so that it never resizes.
    final int tableSize = Math.max(1, Integer.highestOneBit(Math.max(1, initialCapacity - 1)) << 1);
    if (size > tableSize - (tableSize >>> 2)) {
      return null;
    }
    final int mask = tableSize - 1;
    final int[] buckets = new int[size];
    final int[] counts = new int[tableSize + 1];
    for (int i = 0; i < size; i++) {
      final int h = keys[i].hashCode();
      final int bucket = (h ^ (h >>> 16)) & mask;
      buckets[i] = bucket;
      if (++counts[bucket + 1] >= TREEIFY_THRESHOLD) {
        return null;
      }
    }
    // A stable counting sort by bucket: within a bucket, HashMap keeps insertion order.
    for (int b = 0; b < tableSize; b++) {
      counts[b + 1] += counts[b];
    }
    final String[] orderedKeys = new String[size];
    final Value[] orderedValues = new Value[size];
    for (int i = 0; i < size; i++) {
      final int position = counts[buckets[i]]++;
      orderedKeys[position] = keys[i];
      orderedValues[position] = values[i];
    }
    return new FrozenMap(
        orderedKeys, orderedValues, size > MAX_SCANNED ? index(orderedKeys) : null);
  }

  /** HashMap restructures a bucket once it reaches this many entries. */
  static final int TREEIFY_THRESHOLD = 8;

  private static int[] index(final String[] keys) {
    // At most half full, so probes stay short.
    final int[] index = new int[Integer.highestOneBit(keys.length) << 2];
    final int mask = index.length - 1;
    for (int i = 0; i < keys.length; i++) {
      int slot = slot(keys[i].hashCode(), mask);
      while (index[slot] != 0) {
        slot = (slot + 1) & mask;
      }
      index[slot] = i + 1;
    }
    return index;
  }

  private static int slot(final int hash, final int mask) {
    final int spread = hash * 0x9E3779B9;
    return (spread ^ (spread >>> 15)) & mask;
  }

  private int find(final Object key) {
    if (!(key instanceof String)) {
      return -1;
    }
    if (index == null) {
      for (int i = 0; i < keys.length; i++) {
        final String k = keys[i];
        if (k.equals(key)) {
          return i;
        }
      }
      return -1;
    }
    final int mask = index.length - 1;
    for (int slot = slot(key.hashCode(), mask); ; slot = (slot + 1) & mask) {
      final int position = index[slot] - 1;
      if (position < 0) {
        return -1;
      }
      final String k = keys[position];
      if (k.equals(key)) {
        return position;
      }
    }
  }

  @Override
  public Value get(final Object key) {
    final int i = find(key);
    return i < 0 ? null : values[i];
  }

  @Override
  public boolean containsKey(final Object key) {
    return find(key) >= 0;
  }

  @Override
  public int size() {
    return keys.length;
  }

  @Override
  public boolean isEmpty() {
    return keys.length == 0;
  }

  @Override
  public Set<Entry<String, Value>> entrySet() {
    return new AbstractSet<Entry<String, Value>>() {
      @Override
      public Iterator<Entry<String, Value>> iterator() {
        return new Positions<Entry<String, Value>>() {
          @Override
          Entry<String, Value> at(final int i) {
            return new SimpleImmutableEntry<>(keys[i], values[i]);
          }
        };
      }

      @Override
      public int size() {
        return keys.length;
      }
    };
  }

  @Override
  public Set<String> keySet() {
    return new AbstractSet<String>() {
      @Override
      public Iterator<String> iterator() {
        return new Positions<String>() {
          @Override
          String at(final int i) {
            return keys[i];
          }
        };
      }

      @Override
      public boolean contains(final Object key) {
        return containsKey(key);
      }

      @Override
      public int size() {
        return keys.length;
      }
    };
  }

  @Override
  public java.util.Collection<Value> values() {
    return new java.util.AbstractCollection<Value>() {
      @Override
      public Iterator<Value> iterator() {
        return new Positions<Value>() {
          @Override
          Value at(final int i) {
            return values[i];
          }
        };
      }

      @Override
      public int size() {
        return keys.length;
      }
    };
  }

  /** Walks the entries' positions in order; read-only. */
  private abstract class Positions<T> implements Iterator<T> {
    private int next;

    abstract T at(int i);

    @Override
    public boolean hasNext() {
      return next < keys.length;
    }

    @Override
    public T next() {
      if (next >= keys.length) {
        throw new NoSuchElementException();
      }
      return at(next++);
    }
  }
}

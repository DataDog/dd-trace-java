package datadog.trace.util;

import java.util.AbstractCollection;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * A read-only {@link Map} built once and then only read, held in flat arrays instead of one node
 * per entry.
 *
 * <p>Reach for it where many small maps are built once and retained, such as the objects of a
 * parsed document: against a {@link java.util.HashMap} behind an unmodifiable wrapper, it drops the
 * node per entry, the table and the wrapper, about 8 B per entry plus the arrays' headers instead
 * of about 40 B per entry plus some 80 B per map. Unlike {@link Map#copyOf}, it keeps the iteration
 * order of the map it copies, and {@code get(null)} misses instead of throwing.
 *
 * <p>Keys and values sit in two parallel arrays, in the source map's iteration order. Up to {@value
 * #MAX_SCANNED} entries, a lookup scans the keys, with no hashing; a larger map adds an
 * open-addressed {@code int[]} index into the arrays, at most half full.
 *
 * <p>The views ({@link #entrySet}, {@link #keySet}, {@link #values}) are created on each call and
 * never cached, so a retained map holds no view objects (a {@code HashMap} keeps each view it has
 * handed out). {@link #forEach} walks the arrays without allocating; prefer it to iterating {@link
 * #entrySet}, which creates an entry per element.
 *
 * <p>Null keys are rejected; null values are allowed. Immutable, so safe to share across threads
 * once published.
 *
 * @param <K> the key type
 * @param <V> the value type
 */
public final class FrozenMap<K, V> extends AbstractMap<K, V> {
  /** Up to this many entries, a lookup scans the keys instead of hashing. */
  public static final int MAX_SCANNED = 8;

  private static final FrozenMap<?, ?> EMPTY = new FrozenMap<>(new Object[0], new Object[0], null);

  private final Object[] keys;
  private final Object[] values;
  // Slots hold an entry's position plus one; zero is empty. Null for a scanned map.
  @Nullable private final int[] index;

  private FrozenMap(final Object[] keys, final Object[] values, @Nullable final int[] index) {
    this.keys = keys;
    this.values = values;
    this.index = index;
  }

  /** Copies the entries of {@code source}, in its iteration order. */
  @SuppressWarnings("unchecked")
  @Nonnull
  public static <K, V> FrozenMap<K, V> copyOf(@Nonnull final Map<? extends K, ? extends V> source) {
    final int size = source.size();
    if (size == 0) {
      return (FrozenMap<K, V>) EMPTY;
    }
    final Object[] keys = new Object[size];
    final Object[] values = new Object[size];
    int i = 0;
    for (final Map.Entry<? extends K, ? extends V> entry : source.entrySet()) {
      keys[i] = Objects.requireNonNull(entry.getKey(), "null key");
      values[i] = entry.getValue();
      i++;
    }
    return new FrozenMap<>(keys, values, size > MAX_SCANNED ? index(keys) : null);
  }

  private static int[] index(final Object[] keys) {
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

  private int find(@Nullable final Object key) {
    if (key == null) {
      return -1;
    }
    final int[] index = this.index;
    if (index == null) {
      final Object[] keys = this.keys;
      for (int i = 0; i < keys.length; i++) {
        if (keys[i].equals(key)) {
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
      if (keys[position].equals(key)) {
        return position;
      }
    }
  }

  @SuppressWarnings("unchecked")
  @Override
  public V get(@Nullable final Object key) {
    final int i = find(key);
    return i < 0 ? null : (V) values[i];
  }

  @Override
  public boolean containsKey(@Nullable final Object key) {
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

  @SuppressWarnings("unchecked")
  @Override
  public void forEach(@Nonnull final BiConsumer<? super K, ? super V> action) {
    final Object[] keys = this.keys;
    final Object[] values = this.values;
    for (int i = 0; i < keys.length; i++) {
      action.accept((K) keys[i], (V) values[i]);
    }
  }

  @Override
  public Set<Entry<K, V>> entrySet() {
    return new AbstractSet<Entry<K, V>>() {
      @Override
      public Iterator<Entry<K, V>> iterator() {
        return new Positions<Entry<K, V>>() {
          @SuppressWarnings("unchecked")
          @Override
          Entry<K, V> at(final int i) {
            return new SimpleImmutableEntry<>((K) keys[i], (V) values[i]);
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
  public Set<K> keySet() {
    return new AbstractSet<K>() {
      @Override
      public Iterator<K> iterator() {
        return new Positions<K>() {
          @SuppressWarnings("unchecked")
          @Override
          K at(final int i) {
            return (K) keys[i];
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
  public Collection<V> values() {
    return new AbstractCollection<V>() {
      @Override
      public Iterator<V> iterator() {
        return new Positions<V>() {
          @SuppressWarnings("unchecked")
          @Override
          V at(final int i) {
            return (V) values[i];
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

package datadog.trace.api.cache;

import datadog.trace.api.Pair;
import java.util.Arrays;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * This is a fixed size cache that only has one operation <code>computeIfAbsent</code>, that is used
 * to retrieve, or store and compute the cached value.
 *
 * <p>If there is a hash collision, the cache uses double hashing two more times to try to find a
 * match or an unused slot.
 *
 * <p>The cache is thread safe, and assumes that the <code>Producer</code> passed into <code>
 * computeIfAbsent</code> is idempotent, or otherwise you might not get back the value you expect
 * from a cache lookup.
 *
 * @param <K> key type
 * @param <V> value type
 */
abstract class FixedSizeCache<K, V> implements DDCache<K, V> {

  static final int MAXIMUM_CAPACITY = 1 << 30;

  private final int mask;
  // This is a cache, so there is no need for volatile, atomics or synchronized.
  // All race conditions here are benign since you always read or write a full
  // Element that can not be modified, and eventually other threads will see it
  // or write the same information at that position, or other information in the
  // case of a collision.
  private final Pair<K, V>[] elements;

  /**
   * Creates a <code>FixedSizeCache</code> that can hold up to <code>capacity</code> elements, if
   * the key hash function has perfect spread.
   *
   * @param capacity the maximum number of elements that the cache can hold
   */
  @SuppressWarnings("unchecked")
  FixedSizeCache(int capacity) {
    int size = calculateSize(capacity);
    this.elements = new Pair[size];
    this.mask = size - 1;
  }

  static int calculateSize(int capacity) {
    if (capacity <= 0) {
      throw new IllegalArgumentException("Cache capacity must be > 0");
    }
    if (capacity > MAXIMUM_CAPACITY) {
      capacity = MAXIMUM_CAPACITY;
    }
    // compute a power of two size for the given capacity
    int n = -1 >>> Integer.numberOfLeadingZeros(capacity - 1);
    return (n < 0) ? 1 : (n >= MAXIMUM_CAPACITY) ? MAXIMUM_CAPACITY : n + 1;
  }

  /**
   * Look up or create and store a value in the cache.
   *
   * <p>If there is a hash collision, the method uses double hashing two more times to try to find a
   * match or an unused slot. If there is no match or empty slot, the first slot is overwritten.
   *
   * @param key the key to look up
   * @param producer how to create a cached value base on the key if the lookup fails
   * @return the cached or created and stored value
   */
  @Override
  public final V computeIfAbsent(K key, Function<K, ? extends V> producer) {
    if (key == null) {
      return null;
    }

    int h = hash(key);
    int firstPos = h & mask;
    V value;

    // try to find a slot or a match 3 times
    for (int i = 1; true; i++) {
      int pos = h & mask;
      Pair<K, V> current = elements[pos];
      if (current == null) {
        // we found an empty slot, so store the value there
        value = produceAndStoreValue(key, producer, pos);
        break;
      } else if (equals(key, current)) {
        // we found a cached key, so use that value
        value = current.getRight();
        break;
      } else if (i == 3) {
        // all 3 slots have been taken, so overwrite the first one
        value = produceAndStoreValue(key, producer, firstPos);
        break;
      }
      // slot was occupied by someone else, so try another slot
      h = rehash(h);
    }
    return value;
  }

  @Override
  public void clear() {
    Arrays.fill(elements, null);
  }

  @Override
  public void visit(BiConsumer<K, V> consumer) {
    for (Pair<K, V> e : elements) {
      if (null != e) {
        consumer.accept(e.getLeft(), e.getRight());
      }
    }
  }

  abstract int hash(K key);

  abstract boolean equals(K key, Pair<K, V> current);

  private V produceAndStoreValue(K key, Function<K, ? extends V> producer, int pos) {
    V value = producer.apply(key);
    elements[pos] = Pair.of(key, value);
    return value;
  }

  static int rehash(int v) {
    int h = v * 0x9e3775cd;
    h = Integer.reverseBytes(h);
    return h * 0x9e3775cd;
  }

  static final class ObjectHash<K, V> extends FixedSizeCache<K, V> {
    ObjectHash(int capacity) {
      super(capacity);
    }

    int hash(K key) {
      return key.hashCode();
    }

    boolean equals(K key, Pair<K, V> current) {
      return key.equals(current.getLeft());
    }
  }

  static final class IdentityHash<K, V> extends FixedSizeCache<K, V> {
    IdentityHash(int capacity) {
      super(capacity);
    }

    int hash(K key) {
      int hash = System.identityHashCode(key);
      return hash - (hash << 7); // multiply by -127 to improve identityHashCode spread
    }

    boolean equals(K key, Pair<K, V> current) {
      return key == current.getLeft();
    }
  }

  static final class ArrayHash<K, V> extends FixedSizeCache<K[], V> {
    ArrayHash(int capacity) {
      super(capacity);
    }

    int hash(K[] key) {
      return Arrays.hashCode(key);
    }

    boolean equals(K[] key, Pair<K[], V> current) {
      return Arrays.equals(key, current.getLeft());
    }
  }
}

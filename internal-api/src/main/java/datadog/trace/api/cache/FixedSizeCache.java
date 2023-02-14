package datadog.trace.api.cache;

import datadog.trace.api.Pair;
import datadog.trace.api.cache.DDPartialKeyCache.Comparator;
import datadog.trace.api.cache.DDPartialKeyCache.Hasher;
import datadog.trace.api.cache.DDPartialKeyCache.Producer;
import java.util.Arrays;
import java.util.function.Function;
import java.util.function.IntFunction;
import javax.annotation.Nullable;

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
 * @param <E> element type
 * @param <K> key type
 * @param <V> value type
 * @param <H> hash function type
 * @param <C> comparison function type
 * @param <P> producer function type
 */
abstract class FixedSizeCache<E, K, V, H, C, P> {

  static final int MAXIMUM_CAPACITY = 1 << 30;

  protected final int mask;
  // This is a cache, so there is no need for volatile, atomics or synchronized.
  // All race conditions here are benign since you always read or write a full
  // Element that can not be modified, and eventually other threads will see it
  // or write the same information at that position, or other information in the
  // case of a collision.
  protected final E[] elements;

  /**
   * Creates a <code>FixedSizeCache</code> that can hold up to <code>capacity</code> elements, if
   * the key hash function has perfect spread.
   *
   * @param capacity the maximum number of elements that the cache can hold
   */
  FixedSizeCache(int capacity, IntFunction<E[]> initializer) {
    if (capacity <= 0) {
      throw new IllegalArgumentException("Cache capacity must be > 0");
    }
    if (capacity > MAXIMUM_CAPACITY) {
      capacity = MAXIMUM_CAPACITY;
    }
    // compute a power of two size for the given capacity
    int n = -1 >>> Integer.numberOfLeadingZeros(capacity - 1);
    n = (n < 0) ? 1 : (n >= MAXIMUM_CAPACITY) ? MAXIMUM_CAPACITY : n + 1;
    this.elements = initializer.apply(n);
    this.mask = n - 1;
  }

  /**
   * Look up or create and store a value in the cache.
   *
   * <p>If there is a hash collision, the method uses double hashing two more times to try to find a
   * match or an unused slot. If there is no match or empty slot, the first slot is overwritten.
   *
   * @param key the key to look up
   * @param m extra parameter that is passed along with the key
   * @param n extra parameter that is passed along with the key
   * @param hasher how to compute the hash using key, m, and n
   * @param comparator how to compare the key, m, n, and value
   * @param producer how to create a cached value base on the key, m, and n if the lookup fails
   * @return the cached or created and stored value
   */
  protected final V internalComputeIfAbsent(
      K key, int m, int n, H hasher, C comparator, P producer) {
    if (key == null) {
      return null;
    }

    int hash = hash(hasher, key, m, n);
    int h = hash;
    int firstPos = h & mask;
    V value;
    // try to find a slot or a match 3 times
    for (int i = 1; true; i++) {
      int pos = h & mask;
      E current = elements[pos];
      if (current == null) {
        // we found an empty slot, so store the value there
        value = produceAndStoreValue(producer, hash, key, m, n, pos);
        break;
      } else if (equals(comparator, hash, key, m, n, current)) {
        // we found a cached key, so use that value
        value = getValue(current);
        break;
      } else if (i == 3) {
        // all 3 slots have been taken, so overwrite the first one
        value = produceAndStoreValue(producer, hash, key, m, n, firstPos);
        break;
      }
      // slot was occupied by someone else, so try another slot
      h = rehash(h);
    }
    return value;
  }

  public void clear() {
    Arrays.fill(elements, null);
  }

  /**
   * Compute the hash code using the hasher function, key, and extra parameters m, n.
   *
   * @param hasher hash function
   * @param key the key to compute the hash for
   * @param m extra parameter passed along with the key
   * @param n extra parameter passed along with the key
   * @return the hash code
   */
  protected abstract int hash(H hasher, K key, int m, int n);

  /**
   * Compare the key and the current element using the comparator function, key, hash code, and
   * extra parameters m, n.
   *
   * @param comparator compare function
   * @param hash hash code
   * @param key the key to compare
   * @param m extra parameter passed along with the key
   * @param n extra parameter passed along with the key
   * @param current the element to compare against
   * @return true if there is a match, false othervise
   */
  protected abstract boolean equals(C comparator, int hash, K key, int m, int n, E current);

  /**
   * Produce a new value using the producer function, key, and extra parameters m, n.
   *
   * @param producer producer function
   * @param key the key to create the value from
   * @param m extra parameter passed along with the key
   * @param n extra parameter passed along with the key
   * @return a new value
   */
  protected abstract V produceValue(P producer, K key, int m, int n);

  /**
   * Create an element using the hash code key, and value.
   *
   * @param hash hash code
   * @param key the key to create the element for
   * @param value the value to create the element for
   * @return a new element
   */
  protected abstract E toElement(int hash, K key, V value);

  /**
   * Get the value from an existing element.
   *
   * @param element
   * @return the value contained in the element.
   */
  protected abstract V getValue(E element);

  private V produceAndStoreValue(P producer, int hash, K key, int m, int n, int pos) {
    V value = produceValue(producer, key, m, n);
    elements[pos] = toElement(hash, key, value);
    return value;
  }

  private static int rehash(int v) {
    int h = v * 0x9e3775cd;
    h = Integer.reverseBytes(h);
    return h * 0x9e3775cd;
  }

  abstract static class FixedSizeKeyValueCache<K, V>
      extends FixedSizeCache<Pair<K, V>, K, V, Void, Void, Function<K, ? extends V>>
      implements DDCache<K, V> {
    public FixedSizeKeyValueCache(int capacity) {
      super(
          capacity,
          n -> {
            @SuppressWarnings(value = {"rawtype", "unchecked"})
            Pair<K, V>[] elements = (Pair<K, V>[]) new Pair[n];
            return elements;
          });
    }

    @Override
    public final V computeIfAbsent(K key, Function<K, ? extends V> producer) {
      return internalComputeIfAbsent(key, 0, 0, null, null, producer);
    }

    @Override
    protected final int hash(Void unused, K key, int m, int n) {
      return hash(key);
    }

    @Override
    protected final boolean equals(Void unused, int hash, K key, int m, int n, Pair<K, V> current) {
      return equals(key, current);
    }

    @Override
    protected final V produceValue(Function<K, ? extends V> producer, K key, int m, int n) {
      return producer.apply(key);
    }

    @Override
    protected final Pair<K, V> toElement(int hash, K key, V value) {
      return Pair.of(key, value);
    }

    @Override
    protected final V getValue(Pair<K, V> element) {
      return element.getRight();
    }

    abstract int hash(K key);

    abstract boolean equals(K key, Pair<K, V> current);
  }

  static final class ObjectHash<K, V> extends FixedSizeKeyValueCache<K, V> {
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

  static final class IdentityHash<K, V> extends FixedSizeKeyValueCache<K, V> {
    IdentityHash(int capacity) {
      super(capacity);
    }

    int hash(K key) {
      return System.identityHashCode(key);
    }

    boolean equals(K key, Pair<K, V> current) {
      return key == current.getLeft();
    }
  }

  static final class ArrayHash<K, V> extends FixedSizeKeyValueCache<K[], V> {
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

  static final class FixedSizePartialKeyCache<K, V>
      extends FixedSizeCache<
          HVElement<V>, K, V, Hasher<K>, Comparator<K, V>, Producer<K, ? extends V>>
      implements DDPartialKeyCache<K, V> {
    public FixedSizePartialKeyCache(int capacity) {
      super(
          capacity,
          n -> {
            @SuppressWarnings(value = {"rawtype", "unchecked"})
            HVElement<V>[] elements = (HVElement<V>[]) new HVElement[n];
            return elements;
          });
    }

    @Override
    public V computeIfAbsent(
        K key,
        int m,
        int n,
        Hasher<K> hasher,
        Comparator<K, V> comparator,
        Producer<K, ? extends V> producer) {
      return internalComputeIfAbsent(key, m, n, hasher, comparator, producer);
    }

    @Override
    protected int hash(Hasher<K> hasher, K key, int m, int n) {
      return hasher.apply(key, m, n);
    }

    @Override
    protected boolean equals(
        Comparator<K, V> comparator, int hash, K key, int m, int n, HVElement<V> current) {
      return hash == current.hash && comparator.test(key, m, n, current.value);
    }

    @Override
    protected V produceValue(Producer<K, ? extends V> producer, K key, int m, int n) {
      return producer.apply(key, m, n);
    }

    @Override
    protected HVElement<V> toElement(int hash, K key, V value) {
      return HVElement.of(hash, value);
    }

    @Override
    protected V getValue(HVElement<V> element) {
      return element.value;
    }
  }

  static final class HVElement<U> {
    static <U> HVElement<U> of(int hash, U value) {
      return new HVElement<>(hash, value);
    }

    final int hash;
    final U value;

    HVElement(int hash, @Nullable U value) {
      this.hash = hash;
      this.value = value;
    }
  }
}

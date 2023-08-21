package datadog.trace.api.cache;

import static datadog.trace.api.cache.FixedSizeCache.calculateSize;
import static datadog.trace.api.cache.FixedSizeCache.rehash;

import java.util.Arrays;
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
 * @param <K> key type
 * @param <V> value type
 */
final class FixedSizePartialKeyCache<K, V> implements DDPartialKeyCache<K, V> {

  private final int mask;
  // This is a cache, so there is no need for volatile, atomics or synchronized.
  // All race conditions here are benign since you always read or write a full
  // Element that can not be modified, and eventually other threads will see it
  // or write the same information at that position, or other information in the
  // case of a collision.
  private final HVElement<V>[] elements;

  /**
   * Creates a <code>FixedSizePartialKeyCache</code> that can hold up to <code>capacity</code>
   * elements, if the key hash function has perfect spread.
   *
   * @param capacity the maximum number of elements that the cache can hold
   */
  @SuppressWarnings("unchecked")
  FixedSizePartialKeyCache(int capacity) {
    int size = calculateSize(capacity);
    this.elements = new HVElement[size];
    this.mask = size - 1;
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
  @Override
  public V computeIfAbsent(
      K key,
      int m,
      int n,
      Hasher<K> hasher,
      Comparator<K, V> comparator,
      Producer<K, ? extends V> producer) {
    if (key == null) {
      return null;
    }

    int hash = hasher.apply(key, m, n);

    int h = hash;
    int firstPos = h & mask;
    V value;

    // try to find a slot or a match 3 times
    for (int i = 1; true; i++) {
      int pos = h & mask;
      HVElement<V> current = elements[pos];
      if (current == null) {
        // we found an empty slot, so store the value there
        value = produceAndStoreValue(producer, hash, key, m, n, pos);
        break;
      } else if (hash == current.hash && comparator.test(key, m, n, current.value)) {
        // we found a cached key, so use that value
        value = current.value;
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

  @Override
  public void clear() {
    Arrays.fill(elements, null);
  }

  private V produceAndStoreValue(
      Producer<K, ? extends V> producer, int hash, K key, int m, int n, int pos) {
    V value = producer.apply(key, hash, m, n);
    elements[pos] = new HVElement<>(hash, value);
    return value;
  }

  static final class HVElement<U> {
    final int hash;
    final U value;

    HVElement(int hash, @Nullable U value) {
      this.hash = hash;
      this.value = value;
    }
  }
}

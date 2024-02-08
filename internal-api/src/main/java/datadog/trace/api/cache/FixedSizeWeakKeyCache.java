package datadog.trace.api.cache;

import static datadog.trace.api.cache.FixedSizeCache.calculateSize;
import static datadog.trace.api.cache.FixedSizeCache.rehash;

import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.function.BiConsumer;
import java.util.function.Function;
import javax.annotation.Nullable;

/**
 * This is a fixed size cache that only has one operation <code>computeIfAbsent</code>, that is used
 * to retrieve, or store and compute the cached value. Keys are weakly referenced and the cache uses
 * {@link System#identityHashCode} for key hashing and equality.
 *
 * <p>If there is a hash collision, the cache uses double hashing two more times to try to find a
 * match or an unused slot. Slots whose keys have been garbage collected are considered unused.
 *
 * <p>The cache is thread safe, and assumes that the <code>Producer</code> passed into <code>
 * computeIfAbsent</code> is idempotent, or otherwise you might not get back the value you expect
 * from a cache lookup.
 *
 * @param <K> key type
 * @param <V> value type
 */
final class FixedSizeWeakKeyCache<K, V> implements DDCache<K, V> {

  private final int mask;
  // This is a cache, so there is no need for volatile, atomics or synchronized.
  // All race conditions here are benign since you always read or write a full
  // Element that can not be modified, and eventually other threads will see it
  // or write the same information at that position, or other information in the
  // case of a collision.
  private final WeakPair<K, V>[] elements;

  /**
   * Creates a <code>FixedSizeWeakKeyCache</code> that can hold up to <code>capacity</code>
   * elements, if the key hash function has perfect spread.
   *
   * @param capacity the maximum number of elements that the cache can hold
   */
  @SuppressWarnings("unchecked")
  FixedSizeWeakKeyCache(int capacity) {
    int size = calculateSize(capacity);
    this.elements = new WeakPair[size];
    this.mask = size - 1;
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

    int hash = System.identityHashCode(key);

    int h = hash - (hash << 7); // multiply by -127 to improve identityHashCode spread
    int firstPos = h & mask;
    V value;

    // try to find a slot or a match 3 times
    for (int i = 1; true; i++) {
      int pos = h & mask;
      WeakPair<K, V> current = elements[pos];
      if (current == null || null == current.get()) {
        // we found an empty slot, so store the value there
        value = produceAndStoreValue(producer, hash, key, pos);
        break;
      } else if (hash == current.hash && key.equals(current.get())) {
        // we found a cached key, so use that value
        value = current.value;
        break;
      } else if (i == 3) {
        // all 3 slots have been taken, so overwrite the first one
        value = produceAndStoreValue(producer, hash, key, firstPos);
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
    for (WeakPair<K, V> e : elements) {
      if (null != e) {
        K key = e.get();
        if (null != key) {
          consumer.accept(key, e.value);
        }
      }
    }
  }

  private V produceAndStoreValue(Function<K, ? extends V> producer, int hash, K key, int pos) {
    V value = producer.apply(key);
    elements[pos] = new WeakPair<>(key, hash, value);
    return value;
  }

  static final class WeakPair<K, V> extends WeakReference<K> {
    final int hash;
    final V value;

    WeakPair(K key, int hash, @Nullable V value) {
      super(key);
      this.hash = hash;
      this.value = value;
    }
  }
}

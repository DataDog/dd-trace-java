package datadog.trace.api.cache;

import static datadog.trace.api.cache.FixedSizeCache.calculateSize;
import static datadog.trace.api.cache.FixedSizeCache.rehash;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.ToIntFunction;

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
 * <p>The cache tracks the total weight of elements inside it, based on the <code>weigher</code>
 * function. Elements will be evicted to maintain the total weight below the configured limit.
 *
 * @param <K> key type
 * @param <V> value type
 */
final class FixedSizeWeightedCache<K, V> implements DDCache<K, V> {

  private final int mask;
  // This is a cache, so there is no need for volatile, atomics or synchronized.
  // All race conditions here are benign since you always read or write a full
  // Element that can not be modified, and eventually other threads will see it
  // or write the same information at that position, or other information in the
  // case of a collision.
  private final Weighed<K, V>[] elements;
  private final ToIntFunction<V> weigher;
  private final int totalWeightLimit;
  private final int totalWeightTarget;

  // only used as a hint, so it doesn't need to be 100% accurate
  private volatile int totalWeightEstimate;

  private static final AtomicIntegerFieldUpdater<FixedSizeWeightedCache>
      TOTAL_WEIGHT_ESTIMATE_UPDATER =
          AtomicIntegerFieldUpdater.newUpdater(FixedSizeWeightedCache.class, "totalWeightEstimate");

  private static final Weighed EVICTED = new Weighed<>(null, null, 0);

  /**
   * Creates a <code>FixedSizeWeightedCache</code> that can hold up to <code>capacity</code>
   * elements, if the key hash function has perfect spread.
   *
   * @param capacity the maximum number of elements that the cache can hold
   * @param weigher the function used to weigh elements before they are cached
   * @param totalWeightLimit the maximum combined weight of cached elements
   */
  @SuppressWarnings("unchecked")
  FixedSizeWeightedCache(int capacity, ToIntFunction<V> weigher, int totalWeightLimit) {
    int size = calculateSize(capacity);
    this.elements = new Weighed[size];
    this.mask = size - 1;
    this.weigher = weigher;
    this.totalWeightLimit = totalWeightLimit;
    this.totalWeightTarget = (int) (0.5 + totalWeightLimit * 0.9); // target 90% of limit
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
  public V computeIfAbsent(K key, Function<K, ? extends V> producer) {
    if (key == null) {
      return null;
    }

    int h = key.hashCode();
    int oldPos = h & mask;
    Weighed<K, V> old = elements[oldPos];
    V value;

    int pos = oldPos;
    Weighed<K, V> current = old;

    // try to find a slot or a match 3 times
    for (int i = 1; true; i++) {
      if (current == null) {
        // we found an empty slot, so store the value there
        value = produceAndStoreValue(key, producer, pos, 0);
        break;
      } else if (current == EVICTED) {
        // use evicted slot instead of first if we can't find a match
        if (old != EVICTED) {
          oldPos = pos;
          old = current;
        }
        // continue search in case our key appears in a later slot
      } else if (key.equals(current.key)) {
        // we found a cached key, so use that value
        value = current.value;
        break;
      }
      if (i == 3) {
        // we've searched all 3 slots, overwrite the first/evicted slot
        value = produceAndStoreValue(key, producer, oldPos, old.weight);
        break;
      }
      // try another slot
      h = rehash(h);
      pos = h & mask;
      current = elements[pos];
    }
    return value;
  }

  @Override
  public void clear() {
    Arrays.fill(elements, null);
    totalWeightEstimate = 0;
  }

  @Override
  public void visit(BiConsumer<K, V> consumer) {
    for (Weighed<K, V> e : elements) {
      if (null != e) {
        consumer.accept(e.key, e.value);
      }
    }
  }

  private V produceAndStoreValue(K key, Function<K, ? extends V> producer, int pos, int oldWeight) {
    V value = producer.apply(key);
    int weight = weigher.applyAsInt(value);
    if (weight > totalWeightLimit) {
      return value; // too big to cache
    }
    int oldEstimate;
    while ((oldEstimate = totalWeightEstimate) <= totalWeightLimit) {
      int newEstimate = oldEstimate + (weight - oldWeight); // estimate may go up or down
      if (TOTAL_WEIGHT_ESTIMATE_UPDATER.compareAndSet(this, oldEstimate, newEstimate)) {
        elements[pos] = new Weighed<>(key, value, weight);
        if (newEstimate > totalWeightLimit) {
          // totalWeightEstimate is now above the limit, making the cache read-only to others.
          // As we sweep the cache we evict elements to move the estimate back below the limit.
          // When we publish the reduced estimate, the cache becomes writable again.
          beginSweep(pos, weight);
        }
        break;
      }
    }
    return value;
  }

  /**
   * Sweeps the cache re-calculating the total weight, evicting any elements that would tip it over.
   */
  private void beginSweep(int startPos, int startWeight) {
    // sweep forward from updated position, wrapping round to cover all other slots in the cache
    int totalWeight = startWeight;
    for (int i = (startPos + 1) & mask; i != startPos; i = (i + 1) & mask) {
      Weighed<K, V> element = elements[i];
      if (element != null && element != EVICTED) {
        totalWeight += element.weight;
        if (totalWeight > totalWeightTarget) {
          totalWeight -= element.weight;
          elements[i] = EVICTED;
        }
      }
    }
    totalWeightEstimate = totalWeight;
  }

  static final class Weighed<K, V> {
    final K key;
    final V value;
    final int weight;

    Weighed(K key, V value, int weight) {
      this.key = key;
      this.value = value;
      this.weight = weight;
    }
  }
}

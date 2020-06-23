package datadog.trace.core.util;

/**
 * This is a fixed size cache that only has one operation <code>computeIfAbsent</code>, that is used
 * to retrieve, or store and compute the cached value.
 *
 * <p>If there is a hash collision, the cache uses double hashing two more times to try to find a
 * match or an unused slot.
 *
 * <p>The cache is thread safe, and assumes that the <code>Creator</code> passed into <code>
 * computeIfAbsent</code> is idempotent or otherwise you might not get back the value you expect
 * from a cache lookup.
 *
 * @param <K> key type
 * @param <V> value type
 */
public class FixedSizeCache<K, V> {

  public interface Creator<K, V> {
    V create(K key);
  }

  static final int MAXIMUM_CAPACITY = 1 << 30;

  private static final class Node<K, V> {
    private final K key;
    private final V value;

    private Node(K key, V value) {
      this.key = key;
      this.value = value;
    }
  }

  private final int mask;
  // This is a cache, so there is no need for volatile, atomics or synchronized.
  // All race conditions here are benign since you always read or write a full
  // Node that can not be modified, and eventually other threads will see it or
  // write the same information at that position, or other information in the
  // case of a collision.
  private final Node<K, V>[] elements;

  /**
   * Creates a <code>FixedSizeCache</code> that can hold up to <code>capacity</code> elements, if
   * the key hash function has perfect spread.
   *
   * @param capacity the maximum number of elements that the cache can hold
   */
  public FixedSizeCache(int capacity) {
    if (capacity <= 0) {
      throw new IllegalArgumentException("Cache capacity must be > 0");
    }
    if (capacity > MAXIMUM_CAPACITY) {
      capacity = MAXIMUM_CAPACITY;
    }
    // compute a power of two size for the given capacity
    int n = -1 >>> Integer.numberOfLeadingZeros(capacity - 1);
    n = (n < 0) ? 1 : (n >= MAXIMUM_CAPACITY) ? MAXIMUM_CAPACITY : n + 1;
    @SuppressWarnings({"rawtype", "unchecked"})
    Node<K, V>[] lmnts = (Node<K, V>[]) new Node[n];
    this.elements = lmnts;
    this.mask = n - 1;
  }

  /**
   * Look up or create and store a value in the cache.
   *
   * @param key the key to look up
   * @param creator how to create a cached value base on the key if the lookup fails
   * @return the cached or created and stored value
   */
  public V computeIfAbsent(K key, Creator<K, ? extends V> creator) {
    if (key == null) {
      return null;
    }

    int h = key.hashCode();
    int firstPos = h & mask;
    V value = null;
    // try to find a slot or a match 3 times
    for (int i = 1; i <= 3; i++) {
      int pos = h & mask;
      Node<K, V> current = elements[pos];
      if (current == null) {
        // we found an empty slot, so store the value there
        value = createAndStoreValue(key, creator, pos);
        break;
      } else if (key.equals(current.key)) {
        // we found a cached key, so use that value
        value = current.value;
        break;
      } else if (i == 3) {
        // all 3 slots have been taken, so overwrite the first one
        value = createAndStoreValue(key, creator, firstPos);
        break;
      }
      // slot was occupied by someone else, so try another slot
      h = rehash(h);
    }
    return value;
  }

  private V createAndStoreValue(K key, Creator<K, ? extends V> creator, int pos) {
    V value = creator.create(key);
    Node<K, V> node = new Node<>(key, value);
    elements[pos] = node;
    return value;
  }

  private int rehash(int v) {
    int h = v * 0x9e3775cd;
    h = Integer.reverseBytes(h);
    return h * 0x9e3775cd;
  }
}

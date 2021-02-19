package datadog.trace.core.util;

import java.util.LinkedHashMap;
import java.util.Map;

public final class LRUCache<K, V> extends LinkedHashMap<K, V> {

  public interface ExpiryListener<T, U> {
    void accept(Map.Entry<T, U> expired);
  }

  // Copied here since they for some reason are `package` and not `protected` in HashMap
  private static final int DEFAULT_INITIAL_CAPACITY = 1 << 4;
  private static final float DEFAULT_LOAD_FACTOR = 0.75f;

  private final int maxEntries;
  private final ExpiryListener<K, V> expiryListener;

  public LRUCache(int maxEntries) {
    this(DEFAULT_INITIAL_CAPACITY, maxEntries);
  }

  public LRUCache(int initialCapacity, int maxEntries) {
    this(initialCapacity, DEFAULT_LOAD_FACTOR, maxEntries);
  }

  public LRUCache(int initialCapacity, float loadFactor, int maxEntries) {
    this(null, initialCapacity, loadFactor, maxEntries);
  }

  public LRUCache(
      ExpiryListener<K, V> expiryListener, int initialCapacity, float loadFactor, int maxEntries) {
    super(initialCapacity, loadFactor, true); // keep track of access order
    this.maxEntries = maxEntries;
    this.expiryListener = expiryListener;
  }

  @Override
  protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
    boolean expire = size() > maxEntries;
    if (null != expiryListener && expire) {
      expiryListener.accept(eldest);
    }
    return expire;
  }
}

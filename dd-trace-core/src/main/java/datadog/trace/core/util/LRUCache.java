package datadog.trace.core.util;

import java.util.LinkedHashMap;
import java.util.Map;

public final class LRUCache<K, V> extends LinkedHashMap<K, V> {
  // Copied here since they for some reason are `package` and not `protected` in HashMap
  private static final int DEFAULT_INITIAL_CAPACITY = 1 << 4;
  private static final float DEFAULT_LOAD_FACTOR = 0.75f;

  private final int maxEntries;

  public LRUCache(int maxEntries) {
    this(DEFAULT_INITIAL_CAPACITY, maxEntries);
  }

  public LRUCache(int initialCapacity, int maxEntries) {
    this(initialCapacity, DEFAULT_LOAD_FACTOR, maxEntries);
  }

  public LRUCache(int initialCapacity, float loadFactor, int maxEntries) {
    super(initialCapacity, loadFactor, true); // keep track of access order
    this.maxEntries = maxEntries;
  }

  @Override
  protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
    return size() > maxEntries;
  }
}

package com.datadog.featureflag;

import datadog.trace.api.featureflag.exposure.ExposureEvent;
import java.util.LinkedHashMap;
import java.util.Map;

public class LRUExposureCache implements ExposureCache {

  private final Map<Key, Value> cache;

  public LRUExposureCache(final int capacity) {
    cache = new FIFOCache<>(capacity);
  }

  @Override
  public boolean add(final ExposureEvent event) {
    final Key key = new Key(event);
    final Value oldValue = cache.get(key);
    if (oldValue == null) {
      cache.put(key, new Value(event));
      return true;
    }
    final Value newValue = new Value(event);
    if (!newValue.equals(oldValue)) {
      cache.remove(key); // ensure LRU semantics
      cache.put(key, newValue);
      return true;
    }
    return false;
  }

  @Override
  public Value get(final Key key) {
    return cache.get(key);
  }

  @Override
  public int size() {
    return cache.size();
  }

  private static class FIFOCache<K, V> extends LinkedHashMap<K, V> {

    private final int capacity;

    private FIFOCache(final int capacity) {
      this.capacity = capacity;
    }

    @Override
    protected boolean removeEldestEntry(final Map.Entry<K, V> eldest) {
      return size() > capacity;
    }
  }
}

package com.datadog.featureflag;

import datadog.trace.api.featureflag.exposure.ExposureEvent;
import datadog.trace.core.util.LRUCache;
import java.util.Map;

/**
 * This class is intentionally not thread-safe. Thread safety is ensured by the single-threaded
 * access pattern managed by {@link ExposureWriterImpl.ExposureSerializingHandler}.
 */
public class LRUExposureCache implements ExposureCache {

  private final Map<Key, Value> cache;

  public LRUExposureCache(final int capacity) {
    cache = new LRUCache<>(capacity);
  }

  @Override
  public boolean add(final ExposureEvent event) {
    final Key key = new Key(event);
    final Value value = new Value(event);
    final Value oldValue = cache.put(key, value);
    return oldValue == null || !oldValue.equals(value);
  }

  @Override
  public Value get(final Key key) {
    return cache.get(key);
  }

  @Override
  public int size() {
    return cache.size();
  }
}

package datadog.trace.civisibility.utils;

import datadog.trace.bootstrap.ContextStore;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Substitute {@link ContextStore} that uses strong-references to track contexts. */
public class StrongMapContextStore<K, C> implements ContextStore<K, C> {
  private final ConcurrentMap<K, C> map = new ConcurrentHashMap<>();

  @Override
  public C get(K key) {
    return map.get(key);
  }

  @Override
  public void put(K key, C context) {
    map.put(key, context);
  }

  @Override
  public C getOrPut(K key, C context) {
    return map.computeIfAbsent(key, k -> context);
  }

  @Override
  public C getOrCompute(K key, KeyAwareFactory<? super K, C> contextFactory) {
    return map.computeIfAbsent(key, contextFactory::create);
  }

  @Override
  public C remove(K key) {
    return map.remove(key);
  }
}

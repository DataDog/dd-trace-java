package datadog.trace.civisibility.utils;

import datadog.trace.bootstrap.ContextStore;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

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
    C existing = map.putIfAbsent(key, context);
    return existing != null ? existing : context;
  }

  @Override
  public C getOrCompute(K key, Function<? super K, C> contextFactory) {
    return map.computeIfAbsent(key, contextFactory);
  }

  @Override
  public C remove(K key) {
    return map.remove(key);
  }
}

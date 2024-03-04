package datadog.trace.civisibility.utils;

import datadog.trace.bootstrap.ContextStore;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class ConcurrentHashMapContextStore<K, C> implements ContextStore<K, C> {

  private final ConcurrentMap<K, C> m = new ConcurrentHashMap<>();

  @Override
  public C get(K key) {
    return m.get(key);
  }

  @Override
  public void put(K key, C context) {
    m.put(key, context);
  }

  @Override
  public C putIfAbsent(K key, C context) {
    return m.computeIfAbsent(key, k -> context);
  }

  @Override
  public C putIfAbsent(K key, Factory<C> contextFactory) {
    return m.computeIfAbsent(key, k -> contextFactory.create());
  }

  @Override
  public C computeIfAbsent(K key, KeyAwareFactory<? super K, C> contextFactory) {
    return m.computeIfAbsent(key, contextFactory::create);
  }

  @Override
  public C remove(K key) {
    return m.remove(key);
  }
}

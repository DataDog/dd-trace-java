package datadog.trace.bootstrap.weakmap;

import datadog.trace.bootstrap.ContextStore;

/**
 * Weak {@link ContextStore} that acts as a fall-back when field-injection isn't possible.
 *
 * <p>This class should be created lazily because it uses weak maps with background cleanup.
 */
public final class WeakMapContextStore<K, V> implements ContextStore<K, V> {
  private static final int DEFAULT_MAX_SIZE = 50_000;

  private final int maxSize;
  private final WeakMap<Object, Object> map = WeakMap.Supplier.newWeakMap();

  public WeakMapContextStore(int maxSize) {
    this.maxSize = maxSize;
  }

  public WeakMapContextStore() {
    this(DEFAULT_MAX_SIZE);
  }

  @Override
  @SuppressWarnings("unchecked")
  public V get(final K key) {
    return (V) map.get(key);
  }

  @Override
  public void put(final K key, final V context) {
    if (map.size() < maxSize) {
      map.put(key, context);
    }
  }

  @Override
  public V putIfAbsent(final K key, final V context) {
    V existingContext = get(key);
    if (null == existingContext) {
      // This whole part with using synchronized is only because
      // we want to avoid prematurely calling the factory if
      // someone else is doing a putIfAbsent at the same time.
      // There is still the possibility that there is a concurrent
      // call to put that will win, but that is indistinguishable
      // from the put happening right after the putIfAbsent.
      synchronized (map) {
        existingContext = get(key);
        if (null == existingContext) {
          existingContext = context;
          put(key, existingContext);
        }
      }
    }
    return existingContext;
  }

  @Override
  public V putIfAbsent(final K key, final Factory<V> contextFactory) {
    return computeIfAbsent(key, contextFactory);
  }

  @Override
  public V computeIfAbsent(K key, KeyAwareFactory<? super K, V> contextFactory) {
    V existingContext = get(key);
    if (null == existingContext) {
      // This whole part with using synchronized is only because
      // we want to avoid prematurely calling the factory if
      // someone else is doing a putIfAbsent at the same time.
      // There is still the possibility that there is a concurrent
      // call to put that will win, but that is indistinguishable
      // from the put happening right after the putIfAbsent.
      synchronized (map) {
        existingContext = get(key);
        if (null == existingContext) {
          existingContext = contextFactory.create(key);
          put(key, existingContext);
        }
      }
    }
    return existingContext;
  }

  @Override
  @SuppressWarnings("unchecked")
  public V remove(final K key) {
    return (V) map.remove(key);
  }

  // Package reachable for testing
  int size() {
    return map.size();
  }
}

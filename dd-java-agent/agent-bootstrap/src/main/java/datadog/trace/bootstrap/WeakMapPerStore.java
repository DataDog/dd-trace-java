package datadog.trace.bootstrap;

import static datadog.trace.bootstrap.FieldBackedContextStores.getContextStore;

import datadog.trace.api.internal.VisibleForTesting;
import datadog.trace.bootstrap.ContextStore.KeyAwareFactory;

/**
 * Weak "map-per-store" fall-back to track contexts when field-injection isn't possible.
 *
 * <p>This class should be created lazily because it uses weak maps with background cleanup.
 */
public final class WeakMapPerStore<K, V> {

  /** Injection helper that immediately delegates to the weak-map for the given context store. */
  public static Object get(final Object key, final int storeId) {
    return getContextStore(storeId).weakStore().get(key);
  }

  /** Injection helper that immediately delegates to the weak-map for the given context store. */
  public static void put(final Object key, final int storeId, final Object context) {
    getContextStore(storeId).weakStore().put(key, context);
  }

  private static final int MAX_SIZE = 50_000;

  private final WeakMap<Object, Object> map = WeakMap.Supplier.newWeakMap();

  WeakMapPerStore() {}

  @SuppressWarnings("unchecked")
  V get(final K key) {
    return (V) map.get(key);
  }

  void put(final K key, final V context) {
    if (map.size() < MAX_SIZE) {
      map.put(key, context);
    }
  }

  V getOrPut(final K key, final V context) {
    V existingContext = get(key);
    if (null == existingContext) {
      // This whole part with using synchronized is only because
      // we want to avoid prematurely calling the factory if
      // someone else is doing a getOrPut at the same time.
      // There is still the possibility that there is a concurrent
      // call to put that will win, but that is indistinguishable
      // from the put happening right after the getOrPut.
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

  V getOrCompute(K key, KeyAwareFactory<? super K, V> contextFactory) {
    V existingContext = get(key);
    if (null == existingContext) {
      // This whole part with using synchronized is only because
      // we want to avoid prematurely calling the factory if
      // someone else is doing a getOrCompute at the same time.
      // There is still the possibility that there is a concurrent
      // call to put that will win, but that is indistinguishable
      // from the put happening right after the getOrCompute.
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

  @SuppressWarnings("unchecked")
  V remove(final K key) {
    return (V) map.remove(key);
  }

  @VisibleForTesting
  int size() {
    return map.size();
  }
}

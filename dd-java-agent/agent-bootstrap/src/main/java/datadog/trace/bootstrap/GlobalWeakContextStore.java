package datadog.trace.bootstrap;

import datadog.trace.api.Platform;
import datadog.trace.util.AgentTaskScheduler;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Global weak {@link ContextStore} that acts as a fall-back when field-injection isn't possible.
 */
@SuppressWarnings("unchecked")
final class GlobalWeakContextStore<K, V> implements ContextStore<K, V> {

  // global map of weak (key + store-id) wrappers mapped to context values
  private static final Map<Object, Object> globalMap = new ConcurrentHashMap<>();

  // stale key wrappers that are now eligible for collection
  private static final ReferenceQueue<Object> staleKeys = new ReferenceQueue<>();

  private static final long CLEAN_FREQUENCY_SECONDS = 1;

  private static final int MAX_KEYS_CLEANED_PER_CYCLE = 1_000;

  static {
    if (!Platform.isNativeImageBuilder()) {
      AgentTaskScheduler.get()
          .scheduleAtFixedRate(
              GlobalWeakContextStore::cleanStaleKeys,
              CLEAN_FREQUENCY_SECONDS,
              CLEAN_FREQUENCY_SECONDS,
              TimeUnit.SECONDS);
    }
  }

  /** Checks for stale key wrappers and removes them from the global map. */
  static void cleanStaleKeys() {
    int count = 0;
    Reference<?> ref;
    while ((ref = staleKeys.poll()) != null) {
      globalMap.remove(ref);
      if (++count >= MAX_KEYS_CLEANED_PER_CYCLE) {
        break; // limit work done per call
      }
    }
  }

  private final int storeId;

  GlobalWeakContextStore(int storeId) {
    this.storeId = storeId;
  }

  @Override
  public V get(K key) {
    return (V) globalMap.get(new LookupKey(key, storeId));
  }

  @Override
  public void put(K key, V context) {
    globalMap.put(new StoreKey(key, storeId), context);
  }

  @Override
  public V putIfAbsent(K key, V context) {
    LookupKey lookup = new LookupKey(key, storeId);
    Object existing;
    if (null == (existing = globalMap.get(lookup))) {
      // This whole part with using synchronized is only because
      // we want to avoid prematurely calling the factory if
      // someone else is doing a putIfAbsent at the same time.
      // There is still the possibility that there is a concurrent
      // call to put that will win, but that is indistinguishable
      // from the put happening right after the putIfAbsent.
      synchronized (key) {
        if (null == (existing = globalMap.get(lookup))) {
          globalMap.put(new StoreKey(key, storeId), existing = context);
        }
      }
    }
    return (V) existing;
  }

  @Override
  public V putIfAbsent(K key, Factory<V> contextFactory) {
    return computeIfAbsent(key, contextFactory);
  }

  @Override
  public V computeIfAbsent(K key, KeyAwareFactory<? super K, V> contextFactory) {
    LookupKey lookup = new LookupKey(key, storeId);
    Object existing;
    if (null == (existing = globalMap.get(lookup))) {
      // This whole part with using synchronized is only because
      // we want to avoid prematurely calling the factory if
      // someone else is doing a putIfAbsent at the same time.
      // There is still the possibility that there is a concurrent
      // call to put that will win, but that is indistinguishable
      // from the put happening right after the putIfAbsent.
      synchronized (key) {
        if (null == (existing = globalMap.get(lookup))) {
          globalMap.put(new StoreKey(key, storeId), existing = contextFactory.create(key));
        }
      }
    }
    return (V) existing;
  }

  @Override
  public V remove(K key) {
    return (V) globalMap.remove(new LookupKey(key, storeId));
  }

  /** Reference key used to weakly associate a key and store-id with a context value. */
  static final class StoreKey extends WeakReference<Object> {
    final int hash;
    final int storeId;

    StoreKey(Object key, int storeId) {
      super(key, staleKeys);
      this.hash = (31 * storeId) + System.identityHashCode(key);
      this.storeId = storeId;
    }

    @Override
    public int hashCode() {
      return hash;
    }

    @Override
    @SuppressFBWarnings("Eq") // symmetric because it mirrors LookupKey.equals
    public boolean equals(Object o) {
      if (o instanceof LookupKey) {
        return storeId == ((LookupKey) o).storeId && get() == ((LookupKey) o).key;
      } else if (o instanceof StoreKey) {
        return storeId == ((StoreKey) o).storeId && get() == ((StoreKey) o).get();
      } else {
        return false;
      }
    }
  }

  /** Temporary key used for lookup purposes without the reference tracking overhead. */
  static final class LookupKey {
    final Object key;
    final int hash;
    final int storeId;

    LookupKey(Object key, int storeId) {
      this.key = key;
      this.hash = (31 * storeId) + System.identityHashCode(key);
      this.storeId = storeId;
    }

    @Override
    public int hashCode() {
      return hash;
    }

    @Override
    @SuppressFBWarnings("Eq") // symmetric because it mirrors StoreKey.equals
    public boolean equals(Object o) {
      if (o instanceof StoreKey) {
        return storeId == ((StoreKey) o).storeId && key == ((StoreKey) o).get();
      } else if (o instanceof LookupKey) {
        return storeId == ((LookupKey) o).storeId && key == ((LookupKey) o).key;
      } else {
        return false;
      }
    }
  }
}

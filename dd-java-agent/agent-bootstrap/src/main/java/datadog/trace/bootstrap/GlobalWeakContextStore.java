package datadog.trace.bootstrap;

import datadog.trace.api.Platform;
import datadog.trace.bootstrap.ContextStore.KeyAwareFactory;
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
public final class GlobalWeakContextStore {

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

  private GlobalWeakContextStore() {}

  public static Object weakGet(Object key, int storeId) {
    return globalMap.get(new LookupKey(key, storeId));
  }

  public static void weakPut(Object key, int storeId, Object context) {
    if (context != null) {
      globalMap.put(new StoreKey(key, storeId), context);
    } else {
      globalMap.remove(new LookupKey(key, storeId));
    }
  }

  public static Object weakPutIfAbsent(Object key, int storeId, Object context) {
    LookupKey lookupKey = new LookupKey(key, storeId);
    Object existing;
    if (null == (existing = globalMap.get(lookupKey))) {
      // This whole part with using synchronized is only because
      // we want to avoid prematurely calling the factory if
      // someone else is doing a putIfAbsent at the same time.
      // There is still the possibility that there is a concurrent
      // call to put that will win, but that is indistinguishable
      // from the put happening right after the putIfAbsent.
      synchronized (key) {
        if (null == (existing = globalMap.get(lookupKey))) {
          weakPut(key, storeId, existing = context);
        }
      }
    }
    return existing;
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  public static Object weakComputeIfAbsent(
      Object key, int storeId, KeyAwareFactory contextFactory) {
    LookupKey lookupKey = new LookupKey(key, storeId);
    Object existing;
    if (null == (existing = globalMap.get(lookupKey))) {
      // This whole part with using synchronized is only because
      // we want to avoid prematurely calling the factory if
      // someone else is doing a putIfAbsent at the same time.
      // There is still the possibility that there is a concurrent
      // call to put that will win, but that is indistinguishable
      // from the put happening right after the putIfAbsent.
      synchronized (key) {
        if (null == (existing = globalMap.get(lookupKey))) {
          weakPut(key, storeId, existing = contextFactory.create(key));
        }
      }
    }
    return existing;
  }

  public static Object weakRemove(Object key, int storeId) {
    return globalMap.remove(new LookupKey(key, storeId));
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
        LookupKey lookupKey = (LookupKey) o;
        return storeId == lookupKey.storeId && get() == lookupKey.key;
      } else if (o instanceof StoreKey) {
        StoreKey storeKey = (StoreKey) o;
        return storeId == storeKey.storeId && get() == storeKey.get();
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
        StoreKey storeKey = (StoreKey) o;
        return storeId == storeKey.storeId && key == storeKey.get();
      } else if (o instanceof LookupKey) {
        LookupKey lookupKey = (LookupKey) o;
        return storeId == lookupKey.storeId && key == lookupKey.key;
      } else {
        return false;
      }
    }
  }
}

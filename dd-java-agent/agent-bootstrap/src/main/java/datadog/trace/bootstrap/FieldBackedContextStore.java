package datadog.trace.bootstrap;

import datadog.instrument.fieldinject.GlobalObjectStore;
import datadog.trace.api.InstrumenterConfig;
import java.util.function.Function;

/**
 * {@link ContextStore} that attempts to store context in its keys by using bytecode-injected
 * fields. Delegates to a lazy {@link WeakMap} for keys that don't have a field for this store.
 */
public final class FieldBackedContextStore implements ContextStore<Object, Object> {
  private static final boolean MAP_PER_STORE =
      InstrumenterConfig.get().isRuntimeContextMapPerStore();

  final int storeId;

  FieldBackedContextStore(final int storeId) {
    this.storeId = storeId;
  }

  @Override
  public Object get(final Object key) {
    if (key instanceof FieldBackedContextAccessor) {
      return ((FieldBackedContextAccessor) key).$get$__datadogContext$(storeId);
    } else if (MAP_PER_STORE) {
      return weakStore().get(key);
    } else {
      return GlobalObjectStore.get(key, storeId);
    }
  }

  @Override
  public void put(final Object key, final Object context) {
    if (key instanceof FieldBackedContextAccessor) {
      ((FieldBackedContextAccessor) key).$put$__datadogContext$(storeId, context);
    } else if (MAP_PER_STORE) {
      weakStore().put(key, context);
    } else {
      GlobalObjectStore.put(key, storeId, context);
    }
  }

  @Override
  public Object getOrPut(final Object key, final Object context) {
    if (key instanceof FieldBackedContextAccessor) {
      final FieldBackedContextAccessor accessor = (FieldBackedContextAccessor) key;
      Object existingContext = accessor.$get$__datadogContext$(storeId);
      if (null == existingContext) {
        synchronized (accessor) {
          existingContext = accessor.$get$__datadogContext$(storeId);
          if (null == existingContext) {
            existingContext = context;
            accessor.$put$__datadogContext$(storeId, existingContext);
          }
        }
      }
      return existingContext;
    } else if (MAP_PER_STORE) {
      return weakStore().getOrPut(key, context);
    } else {
      return GlobalObjectStore.getOrPut(key, storeId, context);
    }
  }

  @Override
  public Object getOrCompute(Object key, Function<? super Object, Object> contextFactory) {
    if (key instanceof FieldBackedContextAccessor) {
      final FieldBackedContextAccessor accessor = (FieldBackedContextAccessor) key;
      Object existingContext = accessor.$get$__datadogContext$(storeId);
      if (null == existingContext) {
        synchronized (accessor) {
          existingContext = accessor.$get$__datadogContext$(storeId);
          if (null == existingContext) {
            existingContext = contextFactory.apply(key);
            accessor.$put$__datadogContext$(storeId, existingContext);
          }
        }
      }
      return existingContext;
    } else if (MAP_PER_STORE) {
      return weakStore().getOrCompute(key, contextFactory);
    } else {
      return GlobalObjectStore.getOrCompute(key, storeId, contextFactory);
    }
  }

  @Override
  public Object remove(Object key) {
    if (key instanceof FieldBackedContextAccessor) {
      final FieldBackedContextAccessor accessor = (FieldBackedContextAccessor) key;
      Object existingContext = accessor.$get$__datadogContext$(storeId);
      if (null != existingContext) {
        synchronized (accessor) {
          existingContext = accessor.$get$__datadogContext$(storeId);
          if (null != existingContext) {
            accessor.$put$__datadogContext$(storeId, null);
          }
        }
      }
      return existingContext;
    } else if (MAP_PER_STORE) {
      return weakStore().remove(key);
    } else {
      return GlobalObjectStore.remove(key, storeId);
    }
  }

  // only create WeakMap-based fall-back when we need it
  private volatile WeakMapPerStore<Object, Object> weakStore;
  private final Object synchronizationInstance = new Object();

  WeakMapPerStore<Object, Object> weakStore() {
    if (null == weakStore) {
      synchronized (synchronizationInstance) {
        if (null == weakStore) {
          weakStore = new WeakMapPerStore<>();
        }
      }
    }
    return weakStore;
  }
}

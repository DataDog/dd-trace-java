package datadog.trace.bootstrap;

import datadog.instrument.fieldinject.KeyWithValue;
import datadog.instrument.fieldinject.ObjectStoreDispatch;
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
    if (key instanceof KeyWithValue) {
      return ((KeyWithValue) key).$get$__dd_instrument$(storeId);
    } else if (MAP_PER_STORE) {
      return weakStore().get(key);
    } else {
      return ObjectStoreDispatch.get(key, storeId);
    }
  }

  @Override
  public void put(final Object key, final Object context) {
    if (key instanceof KeyWithValue) {
      ((KeyWithValue) key).$put$__dd_instrument$(storeId, context);
    } else if (MAP_PER_STORE) {
      weakStore().put(key, context);
    } else {
      ObjectStoreDispatch.put(key, storeId, context);
    }
  }

  @Override
  public Object getOrPut(final Object key, final Object context) {
    if (key instanceof KeyWithValue) {
      final KeyWithValue accessor = (KeyWithValue) key;
      Object existingContext = accessor.$get$__dd_instrument$(storeId);
      if (null == existingContext) {
        synchronized (accessor) {
          existingContext = accessor.$get$__dd_instrument$(storeId);
          if (null == existingContext) {
            existingContext = context;
            accessor.$put$__dd_instrument$(storeId, existingContext);
          }
        }
      }
      return existingContext;
    } else if (MAP_PER_STORE) {
      return weakStore().getOrPut(key, context);
    } else {
      return ObjectStoreDispatch.getOrPut(key, storeId, context);
    }
  }

  @Override
  public Object getOrCompute(Object key, Function<? super Object, Object> contextFactory) {
    if (key instanceof KeyWithValue) {
      final KeyWithValue accessor = (KeyWithValue) key;
      Object existingContext = accessor.$get$__dd_instrument$(storeId);
      if (null == existingContext) {
        synchronized (accessor) {
          existingContext = accessor.$get$__dd_instrument$(storeId);
          if (null == existingContext) {
            existingContext = contextFactory.apply(key);
            accessor.$put$__dd_instrument$(storeId, existingContext);
          }
        }
      }
      return existingContext;
    } else if (MAP_PER_STORE) {
      return weakStore().getOrCompute(key, contextFactory);
    } else {
      return ObjectStoreDispatch.getOrCompute(key, storeId, contextFactory);
    }
  }

  @Override
  public Object remove(Object key) {
    if (key instanceof KeyWithValue) {
      final KeyWithValue accessor = (KeyWithValue) key;
      Object existingContext = accessor.$get$__dd_instrument$(storeId);
      if (null != existingContext) {
        synchronized (accessor) {
          existingContext = accessor.$get$__dd_instrument$(storeId);
          if (null != existingContext) {
            accessor.$put$__dd_instrument$(storeId, null);
          }
        }
      }
      return existingContext;
    } else if (MAP_PER_STORE) {
      return weakStore().remove(key);
    } else {
      return ObjectStoreDispatch.remove(key, storeId);
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

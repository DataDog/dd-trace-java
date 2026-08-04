package datadog.trace.bootstrap;

import datadog.instrument.fieldinject.GlobalObjectStore;

/**
 * {@link ContextStore} that attempts to store context in its keys by using bytecode-injected
 * fields. Delegates to a lazy {@link WeakMap} for keys that don't have a field for this store.
 */
public final class FieldBackedContextStore implements ContextStore<Object, Object> {
  final int storeId;

  FieldBackedContextStore(final int storeId) {
    this.storeId = storeId;
  }

  @Override
  public Object get(final Object key) {
    if (key instanceof FieldBackedContextAccessor) {
      return ((FieldBackedContextAccessor) key).$get$__datadogContext$(storeId);
    } else {
      return GlobalObjectStore.get(key, storeId);
    }
  }

  @Override
  public void put(final Object key, final Object context) {
    if (key instanceof FieldBackedContextAccessor) {
      ((FieldBackedContextAccessor) key).$put$__datadogContext$(storeId, context);
    } else {
      GlobalObjectStore.put(key, storeId, context);
    }
  }

  @Override
  public Object putIfAbsent(final Object key, final Object context) {
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
    } else {
      return GlobalObjectStore.getOrPut(key, storeId, context);
    }
  }

  @Override
  public Object putIfAbsent(final Object key, final Factory<Object> contextFactory) {
    return computeIfAbsent(key, contextFactory);
  }

  @Override
  public Object computeIfAbsent(
      Object key, KeyAwareFactory<? super Object, Object> contextFactory) {
    if (key instanceof FieldBackedContextAccessor) {
      final FieldBackedContextAccessor accessor = (FieldBackedContextAccessor) key;
      Object existingContext = accessor.$get$__datadogContext$(storeId);
      if (null == existingContext) {
        synchronized (accessor) {
          existingContext = accessor.$get$__datadogContext$(storeId);
          if (null == existingContext) {
            existingContext = contextFactory.create(key);
            accessor.$put$__datadogContext$(storeId, existingContext);
          }
        }
      }
      return existingContext;
    } else {
      return GlobalObjectStore.getOrCompute(key, storeId, contextFactory::create);
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
    } else {
      return GlobalObjectStore.remove(key, storeId);
    }
  }
}

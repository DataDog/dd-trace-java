package datadog.trace.bootstrap;

/**
 * {@link ContextStore} that attempts to store context in its keys by using bytecode-injected
 * fields. Delegates to the global weak store for keys that don't have a field for this store.
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
      return weakStore().get(key);
    }
  }

  @Override
  public void put(final Object key, final Object context) {
    if (key instanceof FieldBackedContextAccessor) {
      ((FieldBackedContextAccessor) key).$put$__datadogContext$(storeId, context);
    } else {
      weakStore().put(key, context);
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
      return weakStore().putIfAbsent(key, context);
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
      return weakStore().computeIfAbsent(key, contextFactory);
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
      return weakStore().remove(key);
    }
  }

  // only create global weak store fall-back when we need it
  private volatile ContextStore<Object, Object> weakStore;
  private final Object synchronizationInstance = new Object();

  ContextStore<Object, Object> weakStore() {
    if (null == weakStore) {
      synchronized (synchronizationInstance) {
        if (null == weakStore) {
          weakStore = new GlobalWeakContextStore<>(storeId);
        }
      }
    }
    return weakStore;
  }
}

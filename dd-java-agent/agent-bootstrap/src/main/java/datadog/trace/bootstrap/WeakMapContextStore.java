package datadog.trace.bootstrap;

final class WeakMapContextStore implements ContextStore<Object, Object> {
  private static final int MAX_SIZE = 50_000;

  private final WeakMap<Object, Object> map = WeakMap.Provider.newWeakMap();

  @Override
  public Object get(final Object key) {
    return map.get(key);
  }

  @Override
  public void put(final Object key, final Object context) {
    if (map.size() < MAX_SIZE) {
      map.put(key, context);
    }
  }

  @Override
  public Object putIfAbsent(final Object key, final Object context) {
    Object existingContext = map.get(key);
    if (null == existingContext) {
      synchronized (map) {
        existingContext = map.get(key);
        if (null == existingContext) {
          existingContext = context;
          put(key, existingContext);
        }
      }
    }
    return existingContext;
  }

  @Override
  public Object putIfAbsent(final Object key, final Factory<Object> contextFactory) {
    Object existingContext = map.get(key);
    if (null == existingContext) {
      synchronized (map) {
        existingContext = map.get(key);
        if (null == existingContext) {
          existingContext = contextFactory.create();
          put(key, existingContext);
        }
      }
    }
    return existingContext;
  }
}

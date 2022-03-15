package datadog.trace.bootstrap;

/**
 * Weak {@link ContextStore} that acts as a fall-back when field-injection isn't possible.
 *
 * <p>This class should be created lazily because it uses weak maps with background cleanup.
 */
final class WeakMapContextStore implements ContextStore<Object, Object> {
  private static final int MAX_SIZE = 50_000;

  private final WeakMap<Object, Object> map = WeakMap.Supplier.newWeakMap();

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

  @Override
  public Object remove(final Object key) {
    return map.remove(key);
  }
}

package datadog.trace.agent.tooling.context;

import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.WeakMap;

/**
 * Template class used to generate the class that accesses stored context using either key
 * instance's own injected field or global hash map if field is not available.
 *
 * @deprecated not used in the new field-injection strategy
 */
@Deprecated
final class ContextStoreImplementationTemplate implements ContextStore<Object, Object> {
  private static final ContextStoreImplementationTemplate INSTANCE =
      new ContextStoreImplementationTemplate();

  private static final int MAX_SIZE = 50_000;

  private volatile WeakMap map;
  private final Object synchronizationInstance = new Object();

  private WeakMap getMap() {
    if (null == map) {
      synchronized (synchronizationInstance) {
        if (null == map) {
          this.map = WeakMap.Provider.newWeakMap();
        }
      }
    }
    return map;
  }

  private ContextStoreImplementationTemplate() {}

  @Override
  public Object get(final Object key) {
    return realGet(key);
  }

  @Override
  public Object putIfAbsent(final Object key, final Object context) {
    Object existingContext = realGet(key);
    if (null != existingContext) {
      return existingContext;
    }
    synchronized (realSynchronizeInstance(key)) {
      existingContext = realGet(key);
      if (null != existingContext) {
        return existingContext;
      }
      realPut(key, context);
      return context;
    }
  }

  @Override
  public Object putIfAbsent(final Object key, final Factory<Object> contextFactory) {
    Object existingContext = realGet(key);
    if (null != existingContext) {
      return existingContext;
    }
    synchronized (realSynchronizeInstance(key)) {
      existingContext = realGet(key);
      if (null != existingContext) {
        return existingContext;
      }
      final Object context = contextFactory.create();
      realPut(key, context);
      return context;
    }
  }

  @Override
  public void put(final Object key, final Object context) {
    realPut(key, context);
  }

  private Object realGet(final Object key) {
    // to be generated
    return null;
  }

  private void realPut(final Object key, final Object value) {
    // to be generated
  }

  private Object realSynchronizeInstance(final Object key) {
    // to be generated
    return null;
  }

  private Object mapGet(final Object key) {
    return getMap().get(key);
  }

  private void mapPut(final Object key, final Object value) {
    WeakMap map = getMap();
    if (map.size() < MAX_SIZE) {
      map.put(key, value);
    }
  }

  private Object mapSynchronizeInstance(final Object key) {
    return synchronizationInstance;
  }

  public static ContextStore getContextStore(final Class keyClass, final Class contextClass) {
    // We do not actually check the keyClass here - but that should be fine since compiler would
    // check things for us.
    return INSTANCE;
  }
}

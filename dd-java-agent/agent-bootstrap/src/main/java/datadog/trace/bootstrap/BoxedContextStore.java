package datadog.trace.bootstrap;

import datadog.instrument.fieldinject.ObjectStoreDispatch;
import java.util.function.Function;

public final class BoxedContextStore implements ContextStore<Object, Object> {
  final int storeId;

  BoxedContextStore(final int storeId) {
    this.storeId = storeId;
  }

  @Override
  public Object get(final Object key) {
    return ObjectStoreDispatch.get(key, storeId);
  }

  @Override
  public void put(final Object key, final Object context) {
    ObjectStoreDispatch.put(key, storeId, context);
  }

  @Override
  public Object getOrPut(final Object key, final Object context) {
    return ObjectStoreDispatch.getOrPut(key, storeId, context);
  }

  @Override
  public Object getOrCompute(Object key, Function<? super Object, Object> contextFactory) {
    return ObjectStoreDispatch.getOrCompute(key, storeId, contextFactory);
  }

  @Override
  public Object remove(Object key) {
    return ObjectStoreDispatch.remove(key, storeId);
  }
}

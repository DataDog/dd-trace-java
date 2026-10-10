package context.benchmark;

import java.util.function.Function;

public final class NonFieldInjectedKey implements Store1Key, Store2Key, Store3Key {
  @Override
  public int read() {
    return -1;
  }

  @Override
  public void write(Object context) {}

  @Override
  public Object getContext(int storeIndex) {
    return null;
  }

  @Override
  public void putContext(int storeIndex, Object context) {}

  @Override
  public Object removeContext(int storeIndex) {
    return null;
  }

  @Override
  public Object getOrPutContext(int storeIndex, Object context) {
    return null;
  }

  @Override
  public Object getOrComputeContext(int storeIndex, Function<Object, Object> contextFactory) {
    return null;
  }

  @Override
  public ContextStoreBenchmarkKey newKey() {
    return new NonFieldInjectedKey();
  }
}

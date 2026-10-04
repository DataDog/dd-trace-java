package context.benchmark;

public final class FieldInjectedKey implements Store1Key, Store2Key, Store3Key {
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
}

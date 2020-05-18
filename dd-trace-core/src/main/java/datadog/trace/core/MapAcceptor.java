package datadog.trace.core;

public interface MapAcceptor<T> {
  void beginMap(int size);

  void acceptValue(String key, T value);

  void endMap();
}

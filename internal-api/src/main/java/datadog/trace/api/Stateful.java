package datadog.trace.api;

public interface Stateful extends AutoCloseable {
  @Override
  void close();
}

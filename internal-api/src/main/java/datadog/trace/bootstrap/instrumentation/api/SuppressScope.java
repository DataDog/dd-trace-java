package datadog.trace.bootstrap.instrumentation.api;

public interface SuppressScope extends AutoCloseable {
  @Override
  void close();
}

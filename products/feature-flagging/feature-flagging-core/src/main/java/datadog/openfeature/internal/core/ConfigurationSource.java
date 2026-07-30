package datadog.openfeature.internal.core;

/** Delivers UFC bytes to a {@link ConfigurationSink}. */
public interface ConfigurationSource extends AutoCloseable {

  void start();

  SourceStatus status();

  @Override
  void close();
}

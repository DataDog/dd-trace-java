package datadog.openfeature.internal.core;

/** Accepts raw UFC bytes from a configuration source. */
public interface ConfigurationSink {

  ApplyResult apply(byte[] content);

  ApplyResult clear();
}

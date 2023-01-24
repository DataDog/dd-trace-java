package datadog.trace.bootstrap.instrumentation.api;

public interface UsmMessage {
  int dataSize();

  boolean validate();
}

package datadog.trace.bootstrap.instrumentation.usm;

public interface UsmMessage {
  int dataSize();

  boolean validate();
}

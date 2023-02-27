package datadog.trace.bootstrap.instrumentation.civisibility;

public interface TestContext {
  long getId();

  Long getParentId();

  void reportChildStatus(String status);

  String getStatus();

  boolean isLocalToCurrentProcess();
}

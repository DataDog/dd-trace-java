package datadog.trace.bootstrap.instrumentation.civisibility;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import javax.annotation.Nullable;

public interface TestContext {
  long getId();

  @Nullable
  Long getParentId();

  void reportChildStatus(String status);

  String getStatus();

  boolean isLocalToCurrentProcess();

  @Nullable
  AgentSpan getSpan();
}

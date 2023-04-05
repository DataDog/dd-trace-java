package datadog.trace.civisibility.context;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import javax.annotation.Nullable;

public interface TestContext {

  Long getId();

  @Nullable
  Long getParentId();

  void reportChildStatus(String status);

  String getStatus();

  boolean isLocalToCurrentProcess();

  @Nullable
  AgentSpan getSpan();
}

package datadog.trace.api.civisibility.events.impl;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import javax.annotation.Nullable;

class ParentProcessTestContext extends AbstractTestContext implements TestContext {

  private final long sessionId;
  private final long moduleId;

  public ParentProcessTestContext(long sessionId, long moduleId) {
    this.sessionId = sessionId;
    this.moduleId = moduleId;
  }

  @Override
  public long getId() {
    return moduleId;
  }

  @Override
  public Long getParentId() {
    return sessionId;
  }

  @Override
  public boolean isLocalToCurrentProcess() {
    return false;
  }

  @Nullable
  @Override
  public AgentSpan getSpan() {
    return null;
  }
}

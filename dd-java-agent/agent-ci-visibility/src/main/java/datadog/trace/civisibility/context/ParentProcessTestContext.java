package datadog.trace.civisibility.context;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import javax.annotation.Nullable;

public class ParentProcessTestContext extends AbstractTestContext implements TestContext {

  private final long sessionId;
  private final long moduleId;

  public ParentProcessTestContext(long sessionId, long moduleId) {
    this.sessionId = sessionId;
    this.moduleId = moduleId;
  }

  @Override
  public Long getId() {
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

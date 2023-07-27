package datadog.trace.civisibility.context;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import javax.annotation.Nullable;

public final class EmptyTestContext implements TestContext {

  public static final TestContext INSTANCE = new EmptyTestContext();

  private EmptyTestContext() {}

  @Override
  public Long getId() {
    return null;
  }

  @Nullable
  @Override
  public Long getParentId() {
    return null;
  }

  @Override
  public void reportChildStatus(String status) {}

  @Override
  public String getStatus() {
    return null;
  }

  @Override
  public void reportChildTag(String key, Object value) {}

  @Nullable
  @Override
  public AgentSpan getSpan() {
    return null;
  }
}

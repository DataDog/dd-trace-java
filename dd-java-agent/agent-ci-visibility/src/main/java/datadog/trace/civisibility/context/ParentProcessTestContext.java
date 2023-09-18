package datadog.trace.civisibility.context;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class ParentProcessTestContext extends AbstractTestContext implements TestContext {

  private volatile String testFramework;
  private volatile String testFrameworkVersion;
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

  @Nonnull
  @Override
  public Long getParentId() {
    return sessionId;
  }

  @Nullable
  @Override
  public AgentSpan getSpan() {
    return null;
  }

  @Override
  public void reportChildTag(String key, Object value) {
    // the method leaves room for a
    // proper implementation using a thread-safe map,
    // but for now it's just this,
    // to save some performance costs
    switch (key) {
      case Tags.TEST_FRAMEWORK:
        testFramework = String.valueOf(value);
        break;
      case Tags.TEST_FRAMEWORK_VERSION:
        testFrameworkVersion = String.valueOf(value);
        break;
      default:
        throw new IllegalArgumentException("Unexpected child tag reported: " + key);
    }
  }

  @Nullable
  public Object getChildTag(String key) {
    switch (key) {
      case Tags.TEST_FRAMEWORK:
        return testFramework;
      case Tags.TEST_FRAMEWORK_VERSION:
        return testFrameworkVersion;
      default:
        return null;
    }
  }
}

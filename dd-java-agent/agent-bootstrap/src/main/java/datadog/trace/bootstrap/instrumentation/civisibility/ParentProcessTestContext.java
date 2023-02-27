package datadog.trace.bootstrap.instrumentation.civisibility;

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
}

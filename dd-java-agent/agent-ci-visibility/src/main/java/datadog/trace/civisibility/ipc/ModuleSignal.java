package datadog.trace.civisibility.ipc;

public abstract class ModuleSignal implements Signal {

  protected final long sessionId;
  protected final long moduleId;

  protected ModuleSignal(long sessionId, long moduleId) {
    this.sessionId = sessionId;
    this.moduleId = moduleId;
  }

  public long getSessionId() {
    return sessionId;
  }

  public long getModuleId() {
    return moduleId;
  }
}

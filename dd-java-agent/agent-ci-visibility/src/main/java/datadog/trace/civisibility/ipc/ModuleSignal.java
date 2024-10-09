package datadog.trace.civisibility.ipc;

import datadog.trace.api.DDTraceId;

public abstract class ModuleSignal implements Signal {

  protected final DDTraceId sessionId;
  protected final long moduleId;

  protected ModuleSignal(DDTraceId sessionId, long moduleId) {
    this.sessionId = sessionId;
    this.moduleId = moduleId;
  }

  public DDTraceId getSessionId() {
    return sessionId;
  }

  public long getModuleId() {
    return moduleId;
  }
}

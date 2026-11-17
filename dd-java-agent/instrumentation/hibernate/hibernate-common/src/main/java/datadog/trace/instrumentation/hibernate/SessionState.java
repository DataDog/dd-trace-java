package datadog.trace.instrumentation.hibernate;

import datadog.context.ContextScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;

public class SessionState {
  private final AgentSpan sessionSpan;

  private ContextScope methodScope;
  private boolean hasChildSpan = true;

  public SessionState(AgentSpan sessionSpan) {
    this.sessionSpan = sessionSpan;
  }

  public AgentSpan getSessionSpan() {
    return sessionSpan;
  }

  public ContextScope getMethodScope() {
    return methodScope;
  }

  public void setMethodScope(ContextScope methodScope) {
    this.methodScope = methodScope;
  }

  public boolean hasChildSpan() {
    return hasChildSpan;
  }

  public void setHasChildSpan(boolean hasChildSpan) {
    this.hasChildSpan = hasChildSpan;
  }
}

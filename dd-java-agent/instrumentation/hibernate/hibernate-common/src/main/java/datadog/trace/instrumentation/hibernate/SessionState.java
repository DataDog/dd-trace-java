package datadog.trace.instrumentation.hibernate;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;

public class SessionState {
  private final AgentSpan sessionSpan;

  private AgentScope methodScope;
  private boolean hasChildSpan = true;

  public SessionState(AgentSpan sessionSpan) {
    this.sessionSpan = sessionSpan;
  }

  public AgentSpan getSessionSpan() {
    return sessionSpan;
  }

  public AgentScope getMethodScope() {
    return methodScope;
  }

  public void setMethodScope(AgentScope methodScope) {
    this.methodScope = methodScope;
  }

  public boolean hasChildSpan() {
    return hasChildSpan;
  }

  public void setHasChildSpan(boolean hasChildSpan) {
    this.hasChildSpan = hasChildSpan;
  }
}

package datadog.trace.instrumentation.kafka_streams;

import datadog.context.ContextScope;

public class StreamTaskContext {
  private ContextScope agentScope;
  private String applicationId;

  public StreamTaskContext() {}

  public void setAgentScope(ContextScope agentScope) {
    this.agentScope = agentScope;
  }

  public ContextScope getAgentScope() {
    return agentScope;
  }

  public void setApplicationId(String applicationId) {
    this.applicationId = applicationId;
  }

  public String getApplicationId() {
    return applicationId;
  }
}

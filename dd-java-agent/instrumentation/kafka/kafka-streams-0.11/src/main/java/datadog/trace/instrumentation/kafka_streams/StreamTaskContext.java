package datadog.trace.instrumentation.kafka_streams;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;

public class StreamTaskContext {
  private AgentScope agentScope;
  private String applicationId;

  public StreamTaskContext() {}

  public void setAgentScope(AgentScope agentScope) {
    this.agentScope = agentScope;
  }

  public AgentScope getAgentScope() {
    return agentScope;
  }

  public void setApplicationId(String applicationId) {
    this.applicationId = applicationId;
  }

  public String getApplicationId() {
    return applicationId;
  }
}

package datadog.trace.instrumentation.kafka_streams;

import datadog.context.ContextScope;

public class StreamTaskContext {
  private ContextScope scope;
  private String applicationId;

  public StreamTaskContext() {}

  public void setScope(ContextScope scope) {
    this.scope = scope;
  }

  public ContextScope getScope() {
    return scope;
  }

  public void setApplicationId(String applicationId) {
    this.applicationId = applicationId;
  }

  public String getApplicationId() {
    return applicationId;
  }
}

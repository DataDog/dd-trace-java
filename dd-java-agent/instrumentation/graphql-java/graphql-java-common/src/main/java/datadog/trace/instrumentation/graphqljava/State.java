package datadog.trace.instrumentation.graphqljava;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import graphql.execution.instrumentation.InstrumentationState;

public final class State implements InstrumentationState {
  private AgentSpan requestSpan;
  private String query;

  public AgentSpan getRequestSpan() {
    return requestSpan;
  }

  public void setRequestSpan(AgentSpan requestSpan) {
    this.requestSpan = requestSpan;
  }

  public String getQuery() {
    return query;
  }

  public void setQuery(String query) {
    this.query = query;
  }
}

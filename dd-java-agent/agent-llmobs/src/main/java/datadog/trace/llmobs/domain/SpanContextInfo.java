package datadog.trace.llmobs.domain;

import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;

public class SpanContextInfo {
  private final AgentSpanContext activeContext;
  private final String parentSpanID;

  public static final String ROOT_SPAN_ID = "undefined";

  public SpanContextInfo() {
    this.activeContext = null;
    this.parentSpanID = ROOT_SPAN_ID;
  }

  public SpanContextInfo(AgentSpanContext activeContext, String parentSpanID) {
    this.activeContext = activeContext;
    this.parentSpanID = parentSpanID;
  }

  public boolean isRoot() {
    return this.parentSpanID.equals(ROOT_SPAN_ID);
  }

  public AgentSpanContext getActiveContext() {
    return activeContext;
  }

  public String getParentSpanID() {
    return parentSpanID;
  }
}

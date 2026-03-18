package datadog.trace.instrumentation.websocket.org;

import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;

public class WebsocketAgentSpanContext {
  private AgentSpanContext spanContext;
  private String spanKind;

  public WebsocketAgentSpanContext(AgentSpanContext spanContext,Object spanKindObj) {
    this.spanContext = spanContext;
    if (spanKindObj != null) {
      this.spanKind = spanKindObj.toString();
    }
  }

  public AgentSpanContext getSpanContext() {
    return spanContext;
  }

  public String getSpanKind() {
    return spanKind;
  }
}

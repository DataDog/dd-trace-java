package datadog.trace.instrumentation.netty38;

import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;

public class ChannelTraceContext {
  public static class Factory implements ContextStore.Factory<ChannelTraceContext> {
    public static final Factory INSTANCE = new Factory();

    @Override
    public ChannelTraceContext create() {
      return new ChannelTraceContext();
    }
  }

  AgentScope.Continuation connectionContinuation;
  AgentSpan serverSpan;
  AgentSpan clientSpan;
  AgentSpan clientParentSpan;

  public AgentScope.Continuation getConnectionContinuation() {
    return connectionContinuation;
  }

  public AgentSpan getServerSpan() {
    return serverSpan;
  }

  public AgentSpan getClientSpan() {
    return clientSpan;
  }

  public AgentSpan getClientParentSpan() {
    return clientParentSpan;
  }

  public void setConnectionContinuation(AgentScope.Continuation connectionContinuation) {
    this.connectionContinuation = connectionContinuation;
  }

  public void setServerSpan(AgentSpan serverSpan) {
    this.serverSpan = serverSpan;
  }

  public void setClientSpan(AgentSpan clientSpan) {
    this.clientSpan = clientSpan;
  }

  public void setClientParentSpan(AgentSpan clientParentSpan) {
    this.clientParentSpan = clientParentSpan;
  }
}

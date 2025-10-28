package datadog.trace.instrumentation.mule4;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;

public class SpanState {
  private final AgentSpan eventContextSpan;
  private final SpanState previousState;
  private AgentSpan spanContextSpan;

  public SpanState(AgentSpan eventContextSpan, SpanState previousState) {
    this.eventContextSpan = eventContextSpan;
    this.previousState = previousState;
  }

  public AgentSpan getEventContextSpan() {
    return eventContextSpan;
  }

  public SpanState getPreviousState() {
    return previousState;
  }

  public AgentSpan getSpanContextSpan() {
    return spanContextSpan;
  }

  public SpanState withSpanContextSpan(AgentSpan spanContextSpan) {
    this.spanContextSpan = spanContextSpan;
    return this;
  }

  @Override
  public String toString() {
    return "SpanState{"
        + "eventContextSpan="
        + eventContextSpan
        + ", previousState="
        + previousState
        + ", spanContextSpan="
        + spanContextSpan
        + '}';
  }
}

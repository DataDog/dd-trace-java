package datadog.trace.core.scopemanager;

import datadog.trace.api.Baggage;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;

// TODO Javadoc
public class ScopeContext {

  private final AgentSpan span;
  private final Baggage baggage;

  public static ScopeContext empty() {
    return new ScopeContext(null, null);
  }

  private ScopeContext(AgentSpan span, Baggage baggage) {
    this.span = span;
    this.baggage = baggage;
  }

  AgentSpan span() {
    return this.span;
  }

  Baggage baggage() {
    return this.baggage;
  }

  ScopeContext withSpan(AgentSpan span) {
    return new ScopeContext(span, this.baggage);
  }

  ScopeContext withBaggage(Baggage baggage) {
    return new ScopeContext(this.span, baggage);
  }
}

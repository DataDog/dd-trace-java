package datadog.trace.core.scopemanager;

import datadog.trace.api.Baggage;
import datadog.trace.bootstrap.instrumentation.api.AgentScopeContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScopeManager;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.context.ContextElement;
import java.util.Objects;

/**
 * An immutable context store tied to span scope lifecycle. {@link ScopeContext} can store any
 * {@link ContextElement} instance but context keys are unique. Context is applied using {@link
 * datadog.trace.bootstrap.instrumentation.api.AgentScopeManager#activateContext(AgentScopeContext)}
 * and can be retrieved using {@link AgentScopeManager#active()}.
 */
public class ScopeContext implements AgentScopeContext {
  public static final String SPAN_KEY = "dd-span-key";
  public static final String BAGGAGE_KEY = "dd-baggage-key";
  private final AgentSpan span;
  private final Baggage baggage;

  // TODO Use generic implementation once concept is validated
  private ScopeContext(AgentSpan span, Baggage baggage) {
    this.span = span;
    this.baggage = baggage;
  }

  /**
   * Get an empty {@link ScopeContext} instance.
   *
   * @return An empty {@link ScopeContext} instance.
   */
  public static ScopeContext empty() {
    return new ScopeContext(null, null);
  }

  /**
   * Create a new context inheriting those values with another span.
   *
   * @param span The span to store to the new context.
   * @return The new context instance.
   */
  public static AgentScopeContext fromSpan(AgentSpan span) {
    return new ScopeContext(span, null);
  }

  @Override
  public AgentSpan span() {
    return this.span;
  }

  @Override
  public Baggage baggage() {
    return this.baggage;
  }

  @Override
  public <V extends ContextElement> V getElement(String key) {
    if (key == null) {
      return null;
    }
    switch (key) {
      case SPAN_KEY:
        return (V) this.span;
      case BAGGAGE_KEY:
        return (V) this.baggage;
      default:
        return null;
    }
  }

  @Override
  public <E extends ContextElement> AgentScopeContext with(E element) {
    if (element == null) {
      return this;
    }
    String key = element.contextKey();
    AgentSpan span = this.span;
    Baggage baggage = this.baggage;
    switch (key) {
      case SPAN_KEY:
        span = (AgentSpan) element;
        break;
      case BAGGAGE_KEY:
        baggage = (Baggage) element;
        break;
      default:
        break;
    }
    return new ScopeContext(span, baggage);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    ScopeContext that = (ScopeContext) o;
    return Objects.equals(span, that.span) && Objects.equals(baggage, that.baggage);
  }

  @Override
  public int hashCode() {
    return Objects.hash(span, baggage);
  }
}

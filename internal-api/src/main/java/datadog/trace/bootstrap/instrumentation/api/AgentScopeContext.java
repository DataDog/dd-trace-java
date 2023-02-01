package datadog.trace.bootstrap.instrumentation.api;

import datadog.trace.api.Baggage;
import datadog.trace.context.ContextElement;

/**
 * The immutable context attached to each {@link
 * datadog.trace.bootstrap.instrumentation.api.AgentScopeManager}.
 */
public interface AgentScopeContext {
  /**
   * Get the span of the scope context.
   *
   * @return The span of the scope context, <code>null</code> if no scope in the context.
   */
  AgentSpan span();

  /**
   * Get the {@link Baggage} of the scope context.
   *
   * @return The {@link Baggage} of the scope context, <code>null</code> if no baggage in the
   * context.
   */
  Baggage baggage();

  /**
   * Get a {@link ContextElement} from the context.
   *
   * @param key The element key (from {@link ContextElement#contextKey()}).
   * @param <V> The element type.
   * @return The {@link ContextElement} value, <code>null</code> if no such element from context.
   */
  <V extends ContextElement> V getElement(String key);

  /**
   * Create a new {@link AgentScopeContext} instance appending a {@link ContextElement}.
   *
   * @param element The element to append.
   * @param <V>     The element type to append.
   * @return A new {@link AgentScopeContext} instance.
   */
  <V extends ContextElement> AgentScopeContext with(V element);
}

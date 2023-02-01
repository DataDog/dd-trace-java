package datadog.trace.bootstrap.instrumentation.api;

import datadog.trace.api.Baggage;

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
   *     context.
   */
  Baggage baggage();
}

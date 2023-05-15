package datadog.trace.bootstrap.instrumentation.api;

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

  /**
   * Get a value from the context.
   *
   * @param <T> The element type.
   * @param key The key related to the value to get.
   * @return The requested value, <code>null</code> if no such value for the given key from the
   *     context.
   */
  <T> T get(ContextKey<T> key);

  /**
   * Create a new {@link AgentScopeContext} instance appending a key/value pair.
   *
   * @param <T> The type of value to append.
   * @param key The key related to the value to append.
   * @param value The value to append.
   * @return A new {@link AgentScopeContext} instance.
   */
  <T> AgentScopeContext with(ContextKey<T> key, T value);
}

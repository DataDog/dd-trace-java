package datadog.trace.bootstrap.instrumentation.api;

import javax.annotation.Nullable;

/** Immutable context scoped to an execution unit. */
public interface ScopedContext {

  /** Returns the value stored in this context for the key; {@code null} if there's no value. */
  @Nullable
  <T> T get(ScopedContextKey<T> key);

  /** Creates a new context based on the current context with the additional key-value. */
  <T> ScopedContext with(ScopedContextKey<T> key, T value);

  /**
   * Creates a new context based on the current context with the additional value. The value
   * contains an implicit key which it will use to store itself.
   */
  default ScopedContext with(ImplicitContextKeyed value) {
    return value.storeInto(this);
  }

  /** The span associated with this context; {@code null} if there is no span. */
  @Nullable
  default AgentSpan span() {
    return get(ScopedContextKey.SPAN_KEY);
  }

  /** The baggage to be shared between spans created from the same context. */
  default Baggage baggage() {
    return get(ScopedContextKey.BAGGAGE_KEY);
  }
}

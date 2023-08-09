package datadog.trace.core.scopemanager;

import static datadog.trace.bootstrap.instrumentation.api.AgentSpan.SPAN_CONTEXT_KEY;
import static datadog.trace.bootstrap.instrumentation.api.ContextKey.named;
import static java.lang.Math.max;
import static java.util.Arrays.copyOfRange;

import datadog.trace.bootstrap.instrumentation.api.AgentScopeContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScopeManager;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Baggage;
import datadog.trace.bootstrap.instrumentation.api.ContextKey;
import java.util.Arrays;

/**
 * An immutable context key-value store tied to span scope lifecycle. {@link ScopeContext} can store
 * any values but keys are unique. Setting a new value to an existing key will replace the existing
 * value.<br>
 * Context instances are applied using {@link
 * datadog.trace.bootstrap.instrumentation.api.AgentScopeManager#activateContext(AgentScopeContext)}
 * and can be retrieved using {@link AgentScopeManager#active()}.
 */
public class ScopeContext implements AgentScopeContext {
  public static final ContextKey<Baggage> BAGGAGE_CONTEXT_KEY = named("dd-baggage-key");
  private static final ScopeContext EMPTY = new ScopeContext(new Object[0]);
  /** The generic store. Values are indexed by their {@link ContextKey#index()}. */
  private final Object[] store;

  private ScopeContext(Object[] store) {
    this.store = store;
  }

  /**
   * Get an empty {@link ScopeContext} instance.
   *
   * @return An empty {@link ScopeContext} instance.
   */
  public static ScopeContext empty() {
    return EMPTY;
  }

  /**
   * Create a new context inheriting those values with another span.
   *
   * @param span The span to store to the new context.
   * @return The new context instance.
   */
  public static AgentScopeContext fromSpan(AgentSpan span) {
    return EMPTY.with(SPAN_CONTEXT_KEY, span);
  }

  @Override
  public AgentSpan span() {
    return get(SPAN_CONTEXT_KEY);
  }

  @Override
  public Baggage baggage() {
    return get(BAGGAGE_CONTEXT_KEY);
  }

  @SuppressWarnings("unchecked")
  @Override
  public <T> T get(ContextKey<T> key) {
    return key != null && key.index() < this.store.length ? (T) this.store[key.index()] : null;
  }

  @Override
  public <T> AgentScopeContext with(ContextKey<T> key, T value) {
    if (key == null) {
      return this;
    }
    Object[] newStore = copyOfRange(this.store, 0, max(this.store.length, key.index() + 1));
    newStore[key.index()] = value;
    return new ScopeContext(newStore);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;

    ScopeContext that = (ScopeContext) o;
    return Arrays.equals(this.store, that.store);
  }

  @Override
  public int hashCode() {
    return Arrays.hashCode(this.store);
  }
}

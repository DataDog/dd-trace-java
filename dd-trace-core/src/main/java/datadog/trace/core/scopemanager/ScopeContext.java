package datadog.trace.core.scopemanager;

import static datadog.trace.bootstrap.instrumentation.api.ContextKey.named;

import datadog.trace.bootstrap.instrumentation.api.AgentScopeContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScopeManager;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Baggage;
import datadog.trace.bootstrap.instrumentation.api.ContextKey;
import java.util.Arrays;
import java.util.Objects;

/**
 * An immutable context key-value store tied to span scope lifecycle. {@link ScopeContext} can store
 * any values but keys are unique. Setting a new value to an existing key will replace the existing
 * value.<br>
 * Context instances are applied using {@link
 * datadog.trace.bootstrap.instrumentation.api.AgentScopeManager#activateContext(AgentScopeContext)}
 * and can be retrieved using {@link AgentScopeManager#active()}.
 */
public class ScopeContext implements AgentScopeContext {
  public static final ContextKey<AgentSpan> SPAN_KEY = named("dd-span-key");
  public static final ContextKey<Baggage> BAGGAGE_KEY = named("dd-baggage-key");
  private final AgentSpan span;
  private final Baggage baggage;
  /**
   * Generic key/value store. Even indexes are keys, odd indexes are values. Example: [key1, value1,
   * key2, value2].
   */
  private final Object[] store;

  private ScopeContext(AgentSpan span, Baggage baggage, Object[] store) {
    this.span = span;
    this.baggage = baggage;
    this.store = store;
  }

  /**
   * Get an empty {@link ScopeContext} instance.
   *
   * @return An empty {@link ScopeContext} instance.
   */
  public static ScopeContext empty() {
    return new ScopeContext(null, null, null);
  }

  /**
   * Create a new context inheriting those values with another span.
   *
   * @param span The span to store to the new context.
   * @return The new context instance.
   */
  public static AgentScopeContext fromSpan(AgentSpan span) {
    return new ScopeContext(span, null, null);
  }

  @Override
  public AgentSpan span() {
    return this.span;
  }

  @Override
  public Baggage baggage() {
    return this.baggage;
  }

  @SuppressWarnings("unchecked")
  @Override
  public <T> T get(ContextKey<T> key) {
    if (key == null) {
      return null;
    } else if (key == SPAN_KEY) {
      return (T) this.span;
    } else if (key == BAGGAGE_KEY) {
      return (T) this.baggage;
    } else if (this.store != null) {
      for (int index = 0; index < this.store.length; index += 2) {
        if (this.store[index] == key) {
          return (T) this.store[index + 1];
        }
      }
    }
    return null;
  }

  @Override
  public <T> AgentScopeContext with(ContextKey<T> key, T value) {
    if (key == null) {
      return this;
    }
    AgentSpan newSpan = this.span;
    Baggage newBaggage = this.baggage;
    Object[] newStore = this.store;
    if (key == SPAN_KEY) {
      newSpan = (AgentSpan) value;
    } else if (key == BAGGAGE_KEY) {
      newBaggage = (Baggage) value;
    } else if (this.store == null) {
      newStore = new Object[] {key, value};
    } else {
      boolean updated = false;
      for (int index = 0; index < this.store.length; index += 2) {
        if (this.store[index] == key) {
          newStore = Arrays.copyOfRange(this.store, 0, this.store.length);
          newStore[index + 1] = value;
          updated = true;
          break;
        }
      }
      if (!updated) {
        newStore = Arrays.copyOfRange(this.store, 0, this.store.length + 2);
        newStore[this.store.length] = key;
        newStore[this.store.length + 1] = value;
      }
    }
    return new ScopeContext(newSpan, newBaggage, newStore);
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

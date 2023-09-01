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
import java.util.Map;

/**
 * An immutable context key-value store tied to span scope lifecycle. {@link ScopeContext} can store
 * any values but keys are unique. Setting a new value to an existing key will replace the existing
 * value.<br>
 * Context instances are applied using {@link
 * datadog.trace.bootstrap.instrumentation.api.AgentScopeManager#activateContext(AgentScopeContext)}
 * and can be retrieved using {@link AgentScopeManager#active()}.
 */
public class ScopeContext implements AgentScopeContext {
  /** The {@link ContextKey} to store {@link Baggage} within the {@link ScopeContext}. */
  public static final ContextKey<Baggage> BAGGAGE = named("dd-baggage-key");

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

  // TODO NOT USED. FOR TEST. TO REMOVE
  public static AgentScopeContext merge(
      AgentScopeContext scopeContext1, AgentScopeContext scopeContext2, ContextValueMerger merger) {
    ScopeContext context1 = (ScopeContext) scopeContext1;
    ScopeContext context2 = (ScopeContext) scopeContext2;
    int length1 = context1.store.length;
    int length2 = context2.store.length;
    Object[] result = new Object[Math.max(length1, length2)];
    int commonLength = Math.min(length1, length2);
    for (int i = 0; i < commonLength; i++) {
      result[i] = merger.merge(i, context1.store[i], context2.store[2]);
    }
    if (length1 < length2) {
      System.arraycopy(context2.store, commonLength, result, commonLength, length2 - commonLength);
    } else if (length1 > length2) {
      System.arraycopy(context1.store, commonLength, result, commonLength, length1 - commonLength);
    }
    return new ScopeContext(result);
  }

  // TODO NOT USED. FOR TEST. TO REMOVE
  public interface ContextValueMerger {
    <T> T merge(int keyIndex, T value1, T value2);
  }

  // TODO DOCUMENT
  public static ScopeContext fromMap(Map<ContextKey<?>, Object> content) {
    if (content.isEmpty()) {
      return empty();
    }
    int length = content.keySet().stream().mapToInt(ContextKey::index).max().orElse(0) + 1;
    Object[] store = new Object[length];
    content.forEach((key, value) -> store[key.index()] = value);
    return new ScopeContext(store);
  }

  @Override
  public AgentSpan span() {
    return get(SPAN_CONTEXT_KEY);
  }

  @Override
  public Baggage baggage() {
    return get(BAGGAGE);
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

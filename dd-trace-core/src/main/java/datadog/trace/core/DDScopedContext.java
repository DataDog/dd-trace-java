package datadog.trace.core;

import static java.util.Arrays.copyOfRange;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Baggage;
import datadog.trace.bootstrap.instrumentation.api.ScopedContext;
import datadog.trace.bootstrap.instrumentation.api.ScopedContextKey;
import java.util.Arrays;
import java.util.Objects;
import javax.annotation.Nullable;

/**
 * An immutable context key-value store tied to span scope lifecycle. {@link ScopedContext} can
 * store any values but keys are unique. Setting a new value to an existing key will replace the
 * existing value.
 */
public final class DDScopedContext implements ScopedContext {
  private static final ScopedContext EMPTY =
      new DDScopedContext(null, DDBaggage.empty(), new Object[0]);

  // internal context key indices, matches order in ScopedContextKey
  private static final int SPAN_KEY_INDEX = 0;
  private static final int BAGGAGE_KEY_INDEX = 1;

  @Nullable private final AgentSpan span;
  private final Baggage baggage;
  private final Object[] store;

  private DDScopedContext(@Nullable AgentSpan span, Baggage baggage, Object[] store) {
    this.span = span;
    this.baggage = baggage;
    this.store = store;
  }

  public static ScopedContext empty() {
    return EMPTY;
  }

  @Override
  public AgentSpan span() {
    return span;
  }

  @Override
  public Baggage baggage() {
    return baggage;
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> T get(ScopedContextKey<T> key) {
    switch (key.index()) {
      case SPAN_KEY_INDEX:
        return (T) span;
      case BAGGAGE_KEY_INDEX:
        return (T) baggage;
    }

    int storeIndex = key.index() - 2;

    return storeIndex < store.length ? (T) store[storeIndex] : null;
  }

  @Override
  public <T> ScopedContext with(ScopedContextKey<T> key, T value) {
    switch (key.index()) {
      case SPAN_KEY_INDEX:
        return new DDScopedContext((AgentSpan) value, baggage, store);
      case BAGGAGE_KEY_INDEX:
        return new DDScopedContext(span, (Baggage) value, store);
    }

    int storeIndex = key.index() - 2;
    Object[] newStore = copyOfRange(store, 0, Math.max(store.length, storeIndex + 1));
    newStore[storeIndex] = value;

    return new DDScopedContext(span, baggage, newStore);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    DDScopedContext that = (DDScopedContext) o;
    return Objects.equals(span, that.span)
        && baggage.equals(that.baggage)
        && Arrays.equals(store, that.store);
  }

  @Override
  public int hashCode() {
    int result = 31;
    if (span != null) {
      result += span.hashCode();
    }
    result = 31 * result + baggage.hashCode();
    result = 31 * result + Arrays.hashCode(store);
    return result;
  }
}

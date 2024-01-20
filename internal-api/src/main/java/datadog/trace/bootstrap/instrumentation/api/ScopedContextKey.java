package datadog.trace.bootstrap.instrumentation.api;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Key for indexing values of type {@link T} stored in a {@link ScopedContext}. Keys are compared by
 * identity rather than by name. Each stored type should share its context key somewhere for re-use.
 */
public final class ScopedContextKey<T> {
  private static final AtomicInteger INDEX_GENERATOR = new AtomicInteger(0);

  // internal context keys
  static final ScopedContextKey<AgentSpan> SPAN_KEY = named("dd-span-key");
  static final ScopedContextKey<Baggage> BAGGAGE_KEY = named("dd-baggage-key");

  private final String name;
  private final int index;

  private ScopedContextKey(String name) {
    this.name = name;
    this.index = INDEX_GENERATOR.getAndIncrement();
  }

  /** Creates a new key with the given name. */
  public static <T> ScopedContextKey<T> named(String name) {
    return new ScopedContextKey<>(name);
  }

  /** Returns the index allocated to this key. */
  public int index() {
    return index;
  }

  @Override
  public int hashCode() {
    return index;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    } else if (o == null || getClass() != o.getClass()) {
      return false;
    } else {
      return index == ((ScopedContextKey<?>) o).index;
    }
  }

  @Override
  public String toString() {
    return name + '@' + index;
  }
}

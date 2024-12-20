package datadog.context;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Key for indexing values of type {@link T} stored in a {@link Context}.
 *
 * <p>Keys are compared by identity rather than by name. Each stored context type should either
 * share its key for re-use or implement {@link ImplicitContextKeyed} to keep its key private.
 */
public final class ContextKey<T> {
  private static final AtomicInteger NEXT_INDEX = new AtomicInteger(0);

  private final String name;
  final int index;

  private ContextKey(String name) {
    this.name = name;
    this.index = NEXT_INDEX.getAndIncrement();
  }

  /** Creates a new key with the given name. */
  public static <T> ContextKey<T> named(String name) {
    return new ContextKey<>(name);
  }

  @Override
  public int hashCode() {
    return index;
  }

  // we want identity equality, so no need to override equals()

  @Override
  public String toString() {
    return name;
  }
}

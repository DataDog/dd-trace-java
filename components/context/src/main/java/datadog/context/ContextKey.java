package datadog.context;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link Context} key that maps to a value of type {@link T}.
 *
 * <p>Keys are compared by identity rather than by name. Each stored context type should either
 * share its key for re-use or implement {@link ImplicitContextKeyed} to keep its key private.
 */
public final class ContextKey<T> {
  private static final AtomicInteger NEXT_INDEX = new AtomicInteger(0);

  /** The key name, for debugging purpose only. */
  private final String name;

  /** The key unique context, related to {@link IndexedContext} implementation. */
  final int index;

  private ContextKey(String name) {
    this.name = name;
    this.index = NEXT_INDEX.getAndIncrement();
  }

  /**
   * Creates a new key with the given name.
   *
   * @param name the key name, for debugging purpose only.
   * @return the newly created unique key.
   */
  public static <T> ContextKey<T> named(String name) {
    return new ContextKey<>(name);
  }

  @Override
  public int hashCode() {
    return this.index;
  }

  // we want identity equality, so no need to override equals()

  @Override
  public String toString() {
    return this.name;
  }
}

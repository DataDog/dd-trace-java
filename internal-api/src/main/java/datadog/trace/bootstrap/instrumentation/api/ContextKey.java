package datadog.trace.bootstrap.instrumentation.api;

/**
 * The key to store and retrieve values from ScopedContext key-value storage.
 *
 * @param <T> The type of the value to store.
 */
public class ContextKey<T> {
  private final String name;

  private ContextKey(String name) {
    this.name = name;
  }

  /**
   * Create a key for ScopedContext key-value store.
   *
   * @param name A display name for the key (debug/log purpose only).
   * @param <T> The type of the value to store.
   * @return The key instance to store and retrieve value from ScopedContext key-value storage.
   */
  public static <T> ContextKey<T> named(String name) {
    return new ContextKey<T>(name);
  }

  @Override
  public String toString() {
    return this.name;
  }
}

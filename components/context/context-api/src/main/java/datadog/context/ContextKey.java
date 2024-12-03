package datadog.context;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * The key to store and retrieve values from {@link Context} key-value storage.
 *
 * @param <T> The type of the value to store.
 */
public class ContextKey<T> {
  private static final AtomicInteger INDEX_GENERATOR = new AtomicInteger(0);
  private final String name;
  private final int index;

  private ContextKey(String name) {
    this.name = name;
    this.index = INDEX_GENERATOR.getAndIncrement();
  }

  /**
   * Create a key for ScopedContext key-value store.
   *
   * @param name A display name for the key (debug/log purpose only).
   * @param <T> The type of the value to store.
   * @return The key instance to store and retrieve value from ScopedContext key-value storage.
   */
  public static <T> ContextKey<T> named(String name) {
    return new ContextKey<>(name);
  }

  /**
   * Get the context store index for this key.
   *
   * @return The context store index for this key.
   */
  int index() {
    return this.index;
  }

  @Override
  public String toString() {
    return this.name;
  }
}

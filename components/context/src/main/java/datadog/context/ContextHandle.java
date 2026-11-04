package datadog.context;

import static datadog.context.ContextProviders.manager;

import javax.annotation.Nullable;

/**
 * Read-only access to a context that can be swapped into the current execution unit.
 *
 * <p>A {@link Context} is its own handle. Other handles, like those returned by {@link #swap()} or
 * {@link Context#checkpoint()}, save execution-unit flags for restoration and may wrap the context.
 */
public interface ContextHandle {
  /**
   * Swaps this context with the one attached to the current execution unit.
   *
   * <p>Swapping to a different context resets the current execution unit's flags unless this handle
   * is a checkpoint, in which case the saved flags are restored. Swapping to the current context
   * leaves the current execution unit's flags unchanged.
   *
   * @return a handle to the previously attached context and any saved flags.
   */
  default ContextHandle swap() {
    return manager().swap(this);
  }

  /**
   * Gets the value stored in this context under the given key.
   *
   * @param <T> the type of the value.
   * @param key the key used to store the value.
   * @return the value stored under the key; {@code null} if there is none.
   */
  @Nullable
  <T> T get(ContextKey<T> key);
}

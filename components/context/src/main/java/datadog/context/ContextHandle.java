package datadog.context;

import static datadog.context.ContextProviders.manager;

import javax.annotation.Nullable;

/**
 * A handle for swapping a context and restoring any saved execution-unit flags.
 *
 * <p>A {@link Context} can be used directly as a handle. Handles returned by {@link #swap()} or
 * {@link Context#checkpoint()} can also save execution-unit flags separately from context values.
 */
public interface ContextHandle {
  /**
   * Swaps this context with the one attached to the current execution unit.
   *
   * <p>Swapping to a different context restores this handle's saved flags, or resets the flags if
   * none were saved. Swapping to the same context preserves the current flags, even when this
   * handle contains saved flags.
   *
   * <p>To save the current context without switching, use {@code Context.current().checkpoint()}.
   * Use swaps to switch contexts or restore a saved handle.
   *
   * @return a handle for swapping back to the previous context and restoring any saved flags.
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

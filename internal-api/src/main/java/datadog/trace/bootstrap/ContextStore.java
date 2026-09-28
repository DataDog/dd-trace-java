package datadog.trace.bootstrap;

import java.util.function.Function;
import javax.annotation.Nullable;

/**
 * Interface to represent context storage for instrumentations.
 *
 * <p>Context instances are weakly referenced and will be garbage collected when their corresponding
 * key instance is collected.
 *
 * @param <K> key type to do context lookups
 * @param <C> context type
 */
public interface ContextStore<K, C> {

  /**
   * Factory interface to create context instances
   *
   * @param <C> context type
   */
  interface Factory<C> extends Function<Object, C> {

    /**
     * @return new context instance
     */
    C create();

    default C apply(Object key) {
      return create();
    }
  }

  /**
   * Get context instance for the given key.
   *
   * @param key the context key
   * @return context instance; {@code null} if the key had no context
   */
  @Nullable
  C get(K key);

  /**
   * Unconditionally put new context instance for the given key.
   *
   * @param key the context key
   * @param context context instance to save
   */
  void put(K key, C context);

  /**
   * Gets the context instance for the given key. If no context exists then associate it with the
   * new context.
   *
   * @param key the context key
   * @param context new context instance
   * @return existing context instance if present; otherwise new instance
   */
  C getOrPut(K key, C context);

  /**
   * Gets the context instance for the given key. If no context exists then create one using the
   * given factory and associate it with the key.
   *
   * @param key the context key
   * @param contextFactory factory instance to produce new context instances
   * @return existing context instance if present; otherwise new instance
   */
  default C getOrCreate(K key, Factory<C> contextFactory) {
    return getOrCompute(key, contextFactory);
  }

  /**
   * Gets the context instance for the given key. If no context exists then create one using the
   * given factory and associate it with the key.
   *
   * @param key the context key
   * @param contextFactory factory instance to produce new context instances
   * @return existing context instance if present; otherwise new instance
   */
  C getOrCompute(K key, Function<? super K, C> contextFactory);

  /**
   * Removes the context instance for the given key.
   *
   * @param key the context key
   * @return removed context instance; {@code null} if the key had no context
   */
  @Nullable
  C remove(K key);
}

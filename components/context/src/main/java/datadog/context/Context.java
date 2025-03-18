package datadog.context;

import static datadog.context.ContextProviders.binder;
import static datadog.context.ContextProviders.manager;

import javax.annotation.Nullable;

/**
 * Immutable context scoped to an execution unit or carrier object.
 *
 * <p>There are three ways to get a Context instance:
 *
 * <ul>
 *   <li>The first one is to retrieve the one from the current execution unit using {@link
 *       #current()}. A Context instance can be marked as current using {@link #attach()} within the
 *       execution unit.
 *   <li>The second one is to retrieve one from a carrier object using {@link #from(Object
 *       carrier)}. A Context instance would need to be attached to the carrier first using {@link
 *       #attachTo(Object carrier)} attached.
 *   <li>Finally, the third option is to get the default root Context instance calling {@link
 *       #root()}.
 * </ul>
 *
 * <p>When there is no context attached to the current execution unit, {@link #current()} will
 * return the root context. Similarly, {@link #from(Object carrier)} will return the root context
 * when there is no context attached to the carrier.
 *
 * <p>From a {@link Context} instance, each value is stored and retrieved by its {@link ContextKey},
 * using {@link #with(ContextKey key, Object value)} to store a value (creating a new immutable
 * {@link Context} instance), and {@link #get(ContextKey)} to retrieve it. {@link ContextKey}s
 * represent product of functional areas, and should be created sparingly.
 *
 * <p>{@link Context} instances are thread safe as they are immutable (including their {@link
 * ContextKey}) but the value they hold may themselves be mutable.
 *
 * @see ContextKey
 */
public interface Context {
  /**
   * Returns the root context.
   *
   * @return the initial local context that all contexts extend.
   */
  static Context root() {
    return EmptyContext.INSTANCE;
  }

  /**
   * Returns the context attached to the current execution unit.
   *
   * @return the attached context; {@link #root()} if there is none.
   */
  static Context current() {
    return manager().current();
  }

  /**
   * Attaches this context to the current execution unit.
   *
   * @return a scope to be closed when the context is invalid.
   */
  default ContextScope attach() {
    return manager().attach(this);
  }

  /**
   * Swaps this context with the one attached to current execution unit.
   *
   * @return the previously attached context; {@link #root()} if there was none.
   */
  default Context swap() {
    return manager().swap(this);
  }

  /**
   * Returns the context attached to the given carrier object.
   *
   * @param carrier the carrier object to get the context from.
   * @return the attached context; {@link #root()} if there is none.
   */
  static Context from(Object carrier) {
    return binder().from(carrier);
  }

  /**
   * Attaches this context to the given carrier object.
   *
   * @param carrier the object to carry the context.
   */
  default void attachTo(Object carrier) {
    binder().attachTo(carrier, this);
  }

  /**
   * Detaches the context attached to the given carrier object, leaving it context-less.
   *
   * @param carrier the carrier object to detach its context from.
   * @return the previously attached context; {@link #root()} if there was none.
   */
  static Context detachFrom(Object carrier) {
    return binder().detachFrom(carrier);
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

  /**
   * Creates a copy of this context with the given key-value set.
   *
   * <p>Existing value with the given key will be replaced. Mapping to a {@code null} value will
   * remove the key-value from the context copy.
   *
   * @param <T> the type of the value.
   * @param key the key to store the value.
   * @param value the value to store.
   * @return a new context with the key-value set.
   */
  <T> Context with(ContextKey<T> key, @Nullable T value);

  /**
   * Creates a copy of this context with the given pair of key-values.
   *
   * <p>Existing values with the given keys will be replaced. Mapping to a {@code null} value will
   * remove the key-value from the context copy.
   *
   * @param <T> the type of the first value.
   * @param <U> the type of the second value.
   * @param firstKey the first key to store the first value.
   * @param firstValue the first value to store.
   * @param secondKey the second key to store the second value.
   * @param secondValue the second value to store.
   * @return a new context with the pair of key-values set.
   */
  default <T, U> Context with(
      ContextKey<T> firstKey,
      @Nullable T firstValue,
      ContextKey<U> secondKey,
      @Nullable U secondValue) {
    return with(firstKey, firstValue).with(secondKey, secondValue);
  }

  /**
   * Creates a copy of this context with the implicit key is mapped to the value.
   *
   * @param value the value to store.
   * @return a new context with the implicitly keyed value set.
   * @see #with(ContextKey, Object)
   */
  default Context with(@Nullable ImplicitContextKeyed value) {
    if (value == null) {
      return root();
    }
    return value.storeInto(this);
  }
}

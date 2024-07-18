package datadog.context;

import static java.util.Objects.requireNonNull;

import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * A {@link Context} is an immutable key-value store of execution-scoped data carried across API
 * boundaries and between execution units. It can be propagated between execution units ({@link
 * Thread}s), making it <i>current</i> for the current thread, or by attaching it to an object
 * instance (called <i>carrier</i>, like messages), binding their lifecycles.
 *
 * <p>Context instances are immutable. Setting a new key-value with {@link Context#with(ContextKey,
 * Object)} will create a new instance. Value can be overridden and cleared (by setting them with a
 * {@code null} value).
 *
 * <p>To get a context:
 *
 * <ul>
 *   <li>Use {@link Context#current()} to get the <i>current</i> context (associated with the
 *       current {@link Thread}),
 *   <li>Use {@link Context#from(Object)} to get the context attached to an object (called carrier),
 *   <li>Create a new empty instance with {@link Context#empty()}.
 * </ul>
 *
 * <p>To apply a context:
 *
 * <ul>
 *   <li>Use {@link Context#makeCurrent()} to apply it to the current execution unit ({@link
 *       Thread}),
 *   <li>Use {@link Context#attachTo(Object)} to attach it to a carrier object,
 * </ul>
 *
 * While attaching a context to a carrier object ties their lifecycles, making a {@link Context}
 * current creates a {@link ContextScope}. Closing such scope will deactivate the <i>current</i>
 * context and reactivate the previous one.
 *
 * @see ContextStorage
 * @see ContextBinder
 */
public interface Context {
  /** Retrieves the current context. */
  @Nonnull
  static Context current() {
    return ContextStorage.get().current();
  }

  /**
   * Retrieves the context attached to the given object.
   *
   * @param carrier The object to retrieve context from.
   * @return The attached context, {@code null} if no context attached to the object.
   */
  @Nullable
  static Context from(@Nonnull Object carrier) {
    requireNonNull(carrier, "carrier cannot be null");
    return ContextBinder.get().retrieveFrom(carrier);
  }

  /**
   * Retrieves the context bound to the given object, or supplies a new one.
   *
   * @param carrier The object to retrieve context from.
   * @return The bound context, {@code null} if no context bound to the object.
   */
  @Nonnull
  static Context from(@Nonnull Object carrier, @Nonnull Supplier<Context> supplier) {
    requireNonNull(carrier, "Context carrier cannot be null");
    requireNonNull(supplier, "Context supplier cannot be null");
    Context context = ContextBinder.get().retrieveFrom(carrier);
    if (context == null) {
      context = supplier.get();
    }
    if (context == null) {
      context = Context.empty();
    }
    return context;
  }

  /**
   * Creates an empty context.
   *
   * @return An empty context.
   */
  static @Nonnull Context empty() {
    return ContextStorage.get().empty();
  }

  /**
   * Retrieves the value associated to the given key, {@code null} if there is no value for the key.
   *
   * @param key The key to get the associated value.
   * @param <V> The value type.
   * @return The value associated to the given key, {@code null} if there is no value for the key.
   */
  <V> @Nullable V get(@Nonnull ContextKey<V> key);

  /**
   * Creates a new context with given key-value set, in addition to any existing keys-values.
   *
   * @param key The key associated to the value.
   * @param value The value to store, or {@code null} to remove the existing value associated to the
   *     key.
   * @param <V> The value type.
   * @return A new context with the given key-value set, in addition to any existing key-values.
   */
  <V> @Nonnull Context with(@Nonnull ContextKey<V> key, @Nullable V value);

  /**
   * Makes the context current.
   *
   * @return The scope associated to the current execution.
   */
  @Nonnull
  default ContextScope makeCurrent() {
    return ContextStorage.get().attach(this);
  }

  /**
   * Attaches a context to an object.
   *
   * @param carrier The object to carry the given context.
   */
  default void attachTo(@Nonnull Object carrier) {
    requireNonNull(carrier, "Context Carrier cannot be null");
    ContextBinder.get().attach(this, carrier);
  }
}

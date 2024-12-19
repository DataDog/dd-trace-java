package datadog.context;

import static datadog.context.ContextProviders.binder;
import static datadog.context.ContextProviders.manager;

import javax.annotation.Nullable;

/** Immutable context scoped to an execution unit or carrier object. */
public interface Context {

  /** Returns the root context. */
  static Context root() {
    return manager().root();
  }

  /**
   * Returns the context attached to the current execution unit.
   *
   * @return Attached context; {@link #root()} if there is none
   */
  static Context current() {
    return manager().current();
  }

  /**
   * Attaches this context to the current execution unit.
   *
   * @return Scope to be closed when the context is invalid.
   */
  default ContextScope attach() {
    return manager().attach(this);
  }

  /**
   * Swaps this context with the one attached to current execution unit.
   *
   * @return Previously attached context; {@link #root()} if there was none
   */
  default Context swap() {
    return manager().swap(this);
  }

  /**
   * Returns the context attached to the given carrier object.
   *
   * @return Attached context; {@link #root()} if there is none
   */
  static Context from(Object carrier) {
    return binder().from(carrier);
  }

  /** Attaches this context to the given carrier object. */
  default void attachTo(Object carrier) {
    binder().attachTo(carrier, this);
  }

  /**
   * Detaches the context attached to the given carrier object, leaving it context-less.
   *
   * @return Previously attached context; {@link #root()} if there was none
   */
  static Context detachFrom(Object carrier) {
    return binder().detachFrom(carrier);
  }

  /**
   * Gets the value stored in this context under the given key.
   *
   * @return Value stored under the key; {@code null} if there is no value.
   */
  @Nullable
  <T> T get(ContextKey<T> key);

  /**
   * Creates a new context with the given key-value mapping.
   *
   * @return New context with the key-value mapping.
   */
  <T> Context with(ContextKey<T> key, T value);

  /**
   * Creates a new context with a value that has its own implicit key.
   *
   * @return New context with the implicitly keyed value.
   */
  default Context with(ImplicitContextKeyed value) {
    return value.storeInto(this);
  }
}

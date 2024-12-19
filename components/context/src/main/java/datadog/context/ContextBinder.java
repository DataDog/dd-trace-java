package datadog.context;

/** Binds context to carrier objects. */
public interface ContextBinder {

  /**
   * Returns the context attached to the given carrier object.
   *
   * @return Attached context; {@link Context#root()} if there is none
   */
  Context from(Object carrier);

  /** Attaches the given context to the given carrier object. */
  void attachTo(Object carrier, Context context);

  /**
   * Detaches the context attached to the given carrier object, leaving it context-less.
   *
   * @return Previously attached context; {@link Context#root()} if there was none
   */
  Context detachFrom(Object carrier);

  /** Requests use of a custom {@link ContextBinder}. */
  static void register(ContextBinder binder) {
    ContextProviders.customBinder = binder;
  }
}

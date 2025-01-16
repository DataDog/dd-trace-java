package datadog.context;

/** Binds context to carrier objects. */
public interface ContextBinder {
  /**
   * Returns the context attached to the given carrier object.
   *
   * @param carrier the carrier object to get the context from.
   * @return the attached context; {@link Context#root()} if there is none.
   */
  Context from(Object carrier);

  /**
   * Attaches the given context to the given carrier object.
   *
   * @param carrier the object to carry the context.
   * @param context the context to attach.
   */
  void attachTo(Object carrier, Context context);

  /**
   * Detaches the context attached to the given carrier object, leaving it context-less.
   *
   * @param carrier the carrier object to detach its context from.
   * @return the previously attached context; {@link Context#root()} if there was none.
   */
  Context detachFrom(Object carrier);

  /**
   * Requests use of a custom {@link ContextBinder}.
   *
   * @param binder the binder to use (will replace any other binder in use).
   */
  static void register(ContextBinder binder) {
    ContextProviders.customBinder = binder;
  }
}

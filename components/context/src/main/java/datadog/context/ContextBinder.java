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
   * <p>Once the registered binder is used it cannot be replaced and this method will have no
   * effect. To test different binders, make sure {@link #allowTesting()} is called early on.
   *
   * @param binder the binder to use.
   */
  static void register(ContextBinder binder) {
    ContextProviders.customBinder = binder;
  }

  /**
   * Allow re-registration of custom {@link ContextBinder}s for testing.
   *
   * @return {@code true} if re-registration is allowed; otherwise {@code false}
   */
  static boolean allowTesting() {
    return TestContextBinder.register();
  }
}

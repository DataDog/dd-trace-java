package datadog.context;

/** Manages context across execution units. */
public interface ContextManager {
  /**
   * Returns the context attached to the current execution unit.
   *
   * @return the attached context; {@link Context#root()} if there is none.
   */
  Context current();

  /**
   * Attaches the given context to the current execution unit.
   *
   * @param context the context to attach.
   * @return a scope to be closed when the context is invalid.
   */
  ContextScope attach(Context context);

  /**
   * Swaps the given context with the one attached to current execution unit.
   *
   * @param context the context to swap.
   * @return the previously attached context; {@link Context#root()} if there was none.
   */
  Context swap(Context context);

  /**
   * Requests use of a custom {@link ContextManager}.
   *
   * <p>Once the registered manager is used it cannot be replaced and this method will have no
   * effect. To test different managers, make sure {@link #allowTesting()} is called early on.
   *
   * @param manager the manager to use.
   */
  static void register(ContextManager manager) {
    ContextProviders.customManager = manager;
  }

  /**
   * Allow re-registration of custom {@link ContextManager}s for testing.
   *
   * @return {@code true} if re-registration is allowed; otherwise {@code false}
   */
  static boolean allowTesting() {
    return TestContextManager.register();
  }
}

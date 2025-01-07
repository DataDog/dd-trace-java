package datadog.context;

/** Manages context across execution units. */
public interface ContextManager {
  /**
   * Returns the root context.
   *
   * @return the initial local context that all contexts extend.
   */
  Context root();

  /**
   * Returns the context attached to the current execution unit.
   *
   * @return the attached context; {@link #root()} if there is none.
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
   * @return the previously attached context; {@link #root()} if there was none.
   */
  Context swap(Context context);

  /**
   * Requests use of a custom {@link ContextManager}.
   *
   * @param manager the manager to use (will replace any other manager in use).
   */
  static void register(ContextManager manager) {
    ContextProviders.customManager = manager;
  }
}

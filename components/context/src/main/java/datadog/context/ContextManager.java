package datadog.context;

/** Manages context across execution units. */
public interface ContextManager {

  /** Returns the root context. */
  Context root();

  /**
   * Returns the context attached to the current execution unit.
   *
   * @return Attached context; {@link #root()} if there is none
   */
  Context current();

  /**
   * Attaches the given context to the current execution unit.
   *
   * @return Scope to be closed when the context is invalid.
   */
  ContextScope attach(Context context);

  /**
   * Swaps the given context with the one attached to current execution unit.
   *
   * @return Previously attached context; {@link #root()} if there was none
   */
  Context swap(Context context);

  /**
   * Detaches the context attached to the current execution unit, leaving it context-less.
   *
   * <p>WARNING: prefer {@link ContextScope#close()} to properly restore the surrounding context.
   *
   * @return Previously attached context; {@link #root()} if there was none
   */
  Context detach();

  /** Requests use of a custom {@link ContextManager}. */
  static void register(ContextManager manager) {
    ContextProviders.customManager = manager;
  }

  final class Provided {
    static final ContextManager INSTANCE =
        null != ContextProviders.customManager
            ? ContextProviders.customManager
            : new ThreadLocalContextManager();
  }
}

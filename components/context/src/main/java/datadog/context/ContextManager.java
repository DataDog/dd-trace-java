package datadog.context;

import static datadog.context.ContextProviders.manager;

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
   * Swaps the given context with the one attached to the current execution unit.
   *
   * <p>Swapping to a different context resets the current execution unit's flags unless the handle
   * is a checkpoint, in which case the saved flags are restored. Swapping to the current context
   * leaves the current execution unit's flags unchanged.
   *
   * @param handle a context or a handle returned by this manager.
   * @return a handle to the previously attached context and any saved flags.
   */
  ContextHandle swap(ContextHandle handle);

  /**
   * Saves the given context and the current execution unit's flags for a future {@link #swap}.
   *
   * <p>This does not change the current context or execution-unit flags, or keep a trace open.
   * Capture the original context first if a continuation is needed. Handles returned by swap are
   * already checkpointed.
   *
   * @param context the context to checkpoint.
   * @return a handle to the given context and any saved flags for a future swap.
   */
  ContextHandle checkpoint(Context context);

  /**
   * Captures the given (attached) context so it can be resumed in another execution unit.
   *
   * @return continuation capturing the context; no-op continuation if capture is disabled.
   */
  ContextContinuation capture(Context context);

  /**
   * Returns whether the current execution unit can capture contexts. Capture is enabled by default.
   *
   * <p>Explicit attach and swap operations still work when capture is disabled.
   *
   * @return {@code true} if capture is enabled, {@code false} otherwise.
   */
  boolean isCaptureEnabled();

  /**
   * Enables or disables context capture for the current execution unit.
   *
   * <p>Attaching a different context preserves the capture flag; closing its scope restores the
   * flag from before the attach. Reattaching the current context does not restore the flag on
   * close. When suppressing capture outside an attached scope, save the previous flag and restore
   * it in {@code finally}.
   *
   * <p>See {@link #swap(ContextHandle)} for capture flag behavior across swaps.
   *
   * @param enabled {@code true} to allow capture, {@code false} to disable it.
   */
  void setCaptureEnabled(boolean enabled);

  /**
   * Registers the given listener to receive context events.
   *
   * @param listener the listener to register
   */
  void addListener(ContextListener listener);

  /**
   * Registers the given listener to receive context events.
   *
   * @param listener the listener to register.
   */
  static void register(ContextListener listener) {
    manager().addListener(listener);
  }

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

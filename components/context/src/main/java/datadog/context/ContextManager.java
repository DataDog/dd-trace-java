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
   * Saves the given context and the current execution unit's flags for a later {@link #swap}.
   *
   * <p>The current context and flags stay unchanged. A checkpoint does not keep a trace open. If
   * you need a continuation for propagation to asynchronous work, capture it from the given
   * context.
   *
   * <p>Handles returned by {@link #swap} are already checkpointed.
   *
   * @param context the context to checkpoint.
   * @return a handle to the given context and any saved flags.
   */
  ContextHandle checkpoint(Context context);

  /**
   * Swaps the given context with the one attached to the current execution unit.
   *
   * <p>Swapping to a different context restores the handle's saved flags, or resets the flags if
   * none were saved. Swapping to the same context preserves the current flags, even when the handle
   * contains saved flags.
   *
   * <p>To save the current context without switching, use {@code Context.current().checkpoint()}.
   * Use swaps to switch contexts or restore a saved handle.
   *
   * @param handle a context or a handle returned by this manager.
   * @return a handle for swapping back to the previous context and restoring any saved flags.
   */
  ContextHandle swap(ContextHandle handle);

  /**
   * Captures the given context in a continuation for propagation to asynchronous work.
   *
   * @param context the context to capture.
   * @return a continuation for the context, or a no-op continuation if capture is disabled.
   */
  ContextContinuation capture(Context context);

  /**
   * Returns whether the current execution unit can capture contexts for asynchronous propagation.
   *
   * <p>Capture is enabled by default. Attach and swap operations still work when capture is
   * disabled.
   *
   * @return {@code true} if capture is enabled, {@code false} otherwise.
   */
  boolean isCaptureEnabled();

  /**
   * Enables or disables context capture for asynchronous propagation in the current execution unit.
   *
   * <p>Attaching a different context enables capture. Closing that scope restores the previous
   * flag. Attaching the current context leaves the flag unchanged, and closing that scope does not
   * restore it.
   *
   * <p>To disable capture outside an attached scope, save the previous flag and restore it in
   * {@code finally}.
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

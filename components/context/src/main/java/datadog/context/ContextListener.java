package datadog.context;

/** Listener of context events. */
public interface ContextListener {

  /**
   * Notifies that the context has been updated for the current execution unit.
   *
   * @param before the context before.
   * @param after the context after.
   */
  default void onUpdate(Context before, Context after) {}

  /**
   * Notifies that the given context has been captured by a continuation.
   *
   * @param context the captured context.
   */
  default void onCapture(Context context) {}

  /**
   * Notifies that the given context has been released from a continuation.
   *
   * @param context the released context.
   */
  default void onRelease(Context context) {}
}

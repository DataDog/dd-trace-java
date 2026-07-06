package datadog.context;

/** Listener of context events. */
public interface ContextListener {

  /**
   * Notifies that the given context has been attached to the current execution unit.
   *
   * @param context the attached context.
   */
  default void onAttach(Context context) {}

  /**
   * Notifies that the given context has been detached from the current execution unit.
   *
   * @param context the detached context.
   */
  default void onDetach(Context context) {}

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

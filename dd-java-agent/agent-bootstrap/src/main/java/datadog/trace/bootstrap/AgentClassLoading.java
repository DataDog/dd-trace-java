package datadog.trace.bootstrap;

/**
 * Helps distinguish agent class-loading requests from application requests.
 *
 * <p>Should be used in a short try..finally block around the class-loader call.
 */
public enum AgentClassLoading {
  /** Lightweight class-loader probing to select instrumentations. */
  PROBING_CLASSLOADER,
  /** Locating class resources for Byte-Buddy. */
  LOCATING_CLASS,
  /** Injecting helper classes into class-loaders. */
  INJECTING_HELPERS;

  private static final ThreadLocal<AgentClassLoading> REQUEST = new ThreadLocal<>();

  /**
   * Gets the current agent class-loading request type; {@code null} if there's no agent request.
   */
  public static AgentClassLoading type() {
    return REQUEST.get();
  }

  /** Records that this agent class-loading request has begun. */
  public final void begin() {
    REQUEST.set(this);
  }

  /** Records that this agent class-loading request has ended. */
  public final void end() {
    REQUEST.remove();
  }
}

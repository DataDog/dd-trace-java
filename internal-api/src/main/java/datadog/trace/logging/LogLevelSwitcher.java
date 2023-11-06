package datadog.trace.logging;

/** Enables temporary runtime switching of LogLevel. */
public interface LogLevelSwitcher {

  /**
   * Temporarily switch the current LogLevel to a new LogLevel
   *
   * @param level the LogLevel to switch to
   */
  void switchLevel(LogLevel level);

  /** Restore the LogLevel to the original setting. */
  void restore();

  /** Re-execute logging settings initialization */
  void reinitialize();
}

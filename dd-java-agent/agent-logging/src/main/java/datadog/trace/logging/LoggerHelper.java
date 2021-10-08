package datadog.trace.logging;

import org.slf4j.Marker;

public abstract class LoggerHelper {
  /**
   * Check if the provided {@link LogLevel} and {@link Marker} combination is enabled.
   *
   * @param level the {@link LogLevel} to check
   * @param marker the {@link Marker} to check
   * @return true if logging is enabled for the {@code level} and {@code marker} combination, false
   *     otherwise
   */
  public abstract boolean enabled(LogLevel level, Marker marker);

  /**
   * Log a message at a certain level.
   *
   * <p>The {@link LoggerHelper} can assume that the caller have checked if the {@code level} is
   * {@code enabled}.
   *
   * @param level the {@link LogLevel} to log at
   * @param marker the {@link Marker} to log
   * @param message the message to log
   * @param t the {@link Throwable} to log
   */
  public abstract void log(LogLevel level, Marker marker, String message, Throwable t);
}

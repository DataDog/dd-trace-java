package datadog.trace.logging;

/** Log level enum. */
public enum LogLevel {
  TRACE,
  DEBUG,
  INFO,
  WARN,
  ERROR,
  OFF;

  /**
   * Case insensitive conversion from {@link String} to {@link LogLevel}.
   *
   * <p>Fallback is {@link #INFO}.
   *
   * @param level the {@link LogLevel} as a {@link String}
   * @return the corresponding {@link LogLevel} enum
   */
  public static LogLevel fromString(String level) {
    String upper = level.toUpperCase();
    try {
      return Enum.valueOf(LogLevel.class, upper);
    } catch (Throwable t) {
      // INFO is the fallback
      return LogLevel.INFO;
    }
  }

  /**
   * Check if a {@link LogLevel} is enabled for this {@link LogLevel}.
   *
   * @param level the {@link LogLevel} to check
   * @return true if the {@code level} is enabled, false otherwise
   */
  public boolean isEnabled(LogLevel level) {
    return this.compareTo(level) >= 0;
  }
}

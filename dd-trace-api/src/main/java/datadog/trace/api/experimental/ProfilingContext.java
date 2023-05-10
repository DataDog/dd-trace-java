package datadog.trace.api.experimental;

public interface ProfilingContext {
  /**
   * Sets a context value to be appended to profiling data
   *
   * @param attribute the attribute (must have been registered at startup)
   * @param value the value
   */
  default void setContextValue(String attribute, String value) {}

  /**
   * Clears a context value
   *
   * @param attribute the attribute (must have been registered at startup)
   */
  default void clearContextValue(String attribute) {}
}

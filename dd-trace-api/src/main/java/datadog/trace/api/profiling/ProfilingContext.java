package datadog.trace.api.profiling;

public interface ProfilingContext {

  /**
   * Sets a context value to be appended to profiling data
   *
   * @param attribute the attribute (must have been registered at startup)
   * @param value the value
   */
  default void setContextValue(String attribute, String value) {}

  /**
   * Sets a context value to be appended to profiling data. This overload requires an attribute to
   * have been obtained by calling {@link Profiling#createContextAttribute(String)
   * createContextAttribute} first, but is more efficient.
   *
   * @param attribute the attribute (must have been registered at startup)
   * @param value the value
   */
  default void setContextValue(ProfilingContextAttribute attribute, String value) {}

  /**
   * Clears a context value.
   *
   * @param attribute the attribute (must have been registered at startup)
   */
  default void clearContextValue(String attribute) {}

  /**
   * Clears a context value. This overload requires an attribute to have been obtained by calling
   * {@link Profiling#createContextAttribute(String) createContextAttribute} first, but is more
   * efficient.
   *
   * @param attribute the attribute (must have been registered at startup)
   */
  default void clearContextValue(ProfilingContextAttribute attribute) {}
}

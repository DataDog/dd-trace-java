package datadog.trace.logging;

import java.util.Map;

/** A factory for creating {@link LoggerHelper} instances. */
public abstract class LoggerHelperFactory {
  /**
   * Create a {@link LoggerHelper} for the given {@code name}.
   *
   * @param name the name of the {@code logger}
   * @return the {@link LoggerHelper} for the given name
   */
  public abstract LoggerHelper loggerHelperForName(String name);

  /**
   * Return a map describing all the settings for this {@link LoggerHelperFactory}.
   *
   * @return a {@link Map} describing the settings for this {@link LoggerHelperFactory}
   */
  public abstract Map<String, Object> getSettingsDescription();
}

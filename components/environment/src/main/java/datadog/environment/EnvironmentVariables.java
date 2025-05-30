package datadog.environment;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Safely queries environment variables. */
public final class EnvironmentVariables {
  private EnvironmentVariables() {}

  /**
   * Gets an environment variable value.
   *
   * @param name The environment variable name.
   * @return The environment variable value, {@code null} if missing or can't be retrieved.
   */
  public static @Nullable String get(String name) {
    return getOrDefault(name, null);
  }

  /**
   * Gets an environment variable value, or default value if missing or can't be retrieved.
   *
   * @param name The environment variable name.
   * @param defaultValue The default value to return if the environment variable is missing or can't
   *     be retrieved.
   * @return The environment variable value, {@code defaultValue} if missing or can't be retrieved.
   */
  public static String getOrDefault(@Nonnull String name, String defaultValue) {
    try {
      return System.getenv(name);
    } catch (SecurityException e) {
      return defaultValue;
    }
  }
}

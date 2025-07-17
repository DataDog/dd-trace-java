package datadog.environment;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Safely queries environment variables against security manager.
 *
 * @see <a href="https://docs.oracle.com/javase/8/docs/api/java/lang/SecurityManager.html">Security
 *     Manager</a>
 */
public final class EnvironmentVariables {
  private EnvironmentVariables() {}

  /**
   * Gets an environment variable value.
   *
   * @param name The environment variable name.
   * @return The environment variable value, {@code null} if missing, can't be retrieved, or the
   *     environment variable name is {@code null}.
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
   * @return The environment variable value, {@code defaultValue} if missing, can't be retrieved or
   *     the environment variable name is {@code null}.
   */
  public static String getOrDefault(@Nonnull String name, String defaultValue) {
    if (name == null) {
      return defaultValue;
    }
    try {
      String value = System.getenv(name);
      return value == null ? defaultValue : value;
    } catch (SecurityException e) {
      return defaultValue;
    }
  }
}

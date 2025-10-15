package datadog.environment;

import static java.util.Collections.emptyMap;
import static java.util.Collections.unmodifiableMap;

import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Safely queries environment variables against security manager.
 *
 * @see <a href="https://docs.oracle.com/javase/8/docs/api/java/lang/SecurityManager.html">Security
 *     Manager</a>
 */
public final class EnvironmentVariables {
  private EnvironmentVariables() {}

  public static class EnvironmentVariablesProvider {
    public String get(String name) {
      return System.getenv(name);
    }

    public Map<String, String> getAll() {
      return System.getenv();
    }
  }

  // Make it accessible from tests.
  public static EnvironmentVariablesProvider provider = new EnvironmentVariablesProvider();

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
  public static String getOrDefault(String name, String defaultValue) {
    if (name == null) {
      return defaultValue;
    }
    try {
      String value = provider.get(name);
      return value == null ? defaultValue : value;
    } catch (SecurityException e) {
      return defaultValue;
    }
  }

  /**
   * Gets all environment variables.
   *
   * @return All environment variables captured in an unmodifiable {@link Map}, or an empty {@link
   *     Map} if they can't be retrieved.
   */
  public static Map<String, String> getAll() {
    try {
      return unmodifiableMap(new HashMap<>(provider.getAll()));
    } catch (SecurityException e) {
      return emptyMap();
    }
  }
}

package datadog.environment;

import static java.util.Collections.emptyMap;
import static java.util.Collections.unmodifiableMap;

import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Safely queries system properties against security manager.
 *
 * @see <a href="https://docs.oracle.com/javase/8/docs/api/java/lang/SecurityManager.html">Security
 *     Manager</a>
 */
public final class SystemProperties {
  private SystemProperties() {}

  /**
   * Gets a system property value.
   *
   * @param property The system property name.
   * @return The system property value, {@code null} if missing, can't be retrieved, or the system
   *     property name is {@code null}.
   */
  public static @Nullable String get(String property) {
    return getOrDefault(property, null);
  }

  /**
   * Gets a system property value, or default value if missing or can't be retrieved.
   *
   * @param property The system property name.
   * @param defaultValue The default value to return if the system property is missing or can't be
   *     retrieved.
   * @return The system property value, {@code defaultValue} if missing, can't be retrieved, or the
   *     system property name is {@code null}.
   */
  public static String getOrDefault(String property, String defaultValue) {
    if (property == null) {
      return defaultValue;
    }
    try {
      return System.getProperty(property, defaultValue);
    } catch (SecurityException ignored) {
      return defaultValue;
    }
  }

  /**
   * Convert system properties to an unmodifiable {@link Map}.
   *
   * @return All system properties captured in an unmodifiable {@link Map}, or an empty {@link Map}
   *     if they can't be retrieved.
   */
  public static Map<String, String> asStringMap() {
    try {
      Map<String, String> map = new HashMap<>();
      for (String propertyName : System.getProperties().stringPropertyNames()) {
        map.put(propertyName, System.getProperty(propertyName));
      }
      return unmodifiableMap(map);
    } catch (SecurityException ignored) {
      return emptyMap();
    }
  }

  /**
   * Sets a system property value.
   *
   * @param property The system property name.
   * @param value The system property value to set.
   * @return {@code true} if the system property was successfully set, {@code} false otherwise.
   */
  public static boolean set(String property, String value) {
    if (property == null || value == null) {
      return false;
    }
    try {
      System.setProperty(property, value);
      return true;
    } catch (SecurityException ignored) {
      return false;
    }
  }

  /**
   * Clears a system property.
   *
   * @param property The system property name to clear.
   * @return The previous value of the system property, {@code null} if there was no prior property
   *     and property can't be cleared.
   */
  public static @Nullable String clear(String property) {
    if (property == null) {
      return null;
    }
    try {
      return System.clearProperty(property);
    } catch (SecurityException ignored) {
      return null;
    }
  }
}

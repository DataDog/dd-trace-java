package datadog.environment;

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
   * @return The system property value, {@code null} if missing or can't be retrieved.
   */
  public static String get(String property) {
    return getOrDefault(property, null);
  }

  /**
   * Gets a system property value, or default value if missing or can't be retrieved.
   *
   * @param property The system property name.
   * @param defaultValue The default value to return if the system property is missing or can't be
   *     retrieved.
   * @return The system property value, {@code defaultValue} if missing or can't be retrieved.
   */
  public static String getOrDefault(String property, String defaultValue) {
    try {
      return System.getProperty(property, defaultValue);
    } catch (SecurityException ignored) {
      return defaultValue;
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
    try {
      System.setProperty(property, value);
      return true;
    } catch (SecurityException ignored) {
      return false;
    }
  }
}

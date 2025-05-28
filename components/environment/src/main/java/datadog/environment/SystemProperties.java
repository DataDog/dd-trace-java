package datadog.environment;

/** Safely queries system properties. */
public final class SystemProperties {
  private SystemProperties() {}

  public static String get(String property) {
    return getOrDefault(property, null);
  }

  public static String getOrDefault(String property, String defaultValue) {
    try {
      return System.getProperty(property, defaultValue);
    } catch (SecurityException e) {
      return defaultValue;
    }
  }
}

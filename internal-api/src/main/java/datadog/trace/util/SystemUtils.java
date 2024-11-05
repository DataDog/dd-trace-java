package datadog.trace.util;

public final class SystemUtils {
  public static boolean hasEnvError;
  public static boolean hasPropertyError;

  private SystemUtils() {}

  public static String tryGetEnv(String envVar) {
    return getEnvOrDefault(envVar, null);
  }

  public static String getEnvOrDefault(String envVar, String defaultValue) {
    try {
      return System.getenv(envVar);
    } catch (SecurityException e) {
      hasEnvError = true;
      return defaultValue;
    }
  }

  public static String tryGetProperty(String property) {
    try {
      return System.getProperty(property);
    } catch (SecurityException e) {
      hasPropertyError = true;
      return null;
    }
  }

  public static String getPropertyOrDefault(String property, String defaultValue) {
    try {
      return System.getProperty(property, defaultValue);
    } catch (SecurityException e) {
      hasPropertyError = true;
      return defaultValue;
    }
  }
}

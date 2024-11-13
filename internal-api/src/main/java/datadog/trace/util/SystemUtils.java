package datadog.trace.util;

public final class SystemUtils {

  private SystemUtils() {}

  public static String tryGetEnv(String envVar) {
    return getEnvOrDefault(envVar, null);
  }

  public static String getEnvOrDefault(String envVar, String defaultValue) {
    try {
      return System.getenv(envVar);
    } catch (SecurityException e) {
      return defaultValue;
    }
  }

  public static String tryGetProperty(String property) {
    try {
      return System.getProperty(property);
    } catch (SecurityException e) {
      return null;
    }
  }

  public static String getPropertyOrDefault(String property, String defaultValue) {
    try {
      return System.getProperty(property, defaultValue);
    } catch (SecurityException e) {
      return defaultValue;
    }
  }

  public static boolean canAccessSystemProperties() {
    try {
      // try to access a common system property and see what happens
      System.getProperty("os.name");
    } catch (SecurityException e) {
      return false;
    }
    return true;
  }

  public static boolean canAccessEnvironmentVariables() {
    try {
      // try to access a common env var and see what happens
      System.getenv("DD_ENV");
    } catch (SecurityException e) {
      return false;
    }
    return true;
  }

  public static String trySetProperty(String property, String value) {
    try {
      return System.setProperty(property, value);
    } catch (SecurityException e) {
      return null;
    }
  }
}

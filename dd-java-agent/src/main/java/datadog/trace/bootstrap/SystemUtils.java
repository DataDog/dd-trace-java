package datadog.trace.bootstrap;

public final class SystemUtils {
  private SystemUtils() {}

  public static String tryGetEnv(String envVar) {
    System.out.println("BOOTSTRAP/SYSTEMUTILS - in tryGetEnv method");
    return getEnvOrDefault(envVar, null);
  }

  public static String getEnvOrDefault(String envVar, String defaultValue) {
    try {
      System.out.println("BOOTSTRAP/SYSTEMUTILS - in getEnvOrDefault method try block");
      return System.getenv(envVar);
    } catch (SecurityException e) {
      System.out.println("BOOTSTRAP/SYSTEMUTILS - in getEnvOrDefault method catch block");
      return defaultValue;
    }
  }

  public static String tryGetProperty(String property) {
    try {
      System.out.println("BOOTSTRAP/SYSTEMUTILS - in tryGetProperty method try block");
      return System.getProperty(property);
    } catch (SecurityException e) {
      System.out.println("BOOTSTRAP/SYSTEMUTILS - in tryGetProperty method catch block");
      return null;
    }
  }

  public static String getPropertyOrDefault(String property, String defaultValue) {
    try {
      System.out.println("BOOTSTRAP/SYSTEMUTILS - in getPropertyOrDefault method try block");
      return System.getProperty(property, defaultValue);
    } catch (SecurityException e) {
      System.out.println("BOOTSTRAP/SYSTEMUTILS - in getPropertyOrDefault method catch block");
      return defaultValue;
    }
  }

  public static String trySetProperty(String property, String value) {
    try {
      System.out.println("BOOTSTRAP/SYSTEMUTILS - in trySetProperty method try block");
      return System.getProperty(property);
    } catch (SecurityException e) {
      System.out.println("BOOTSTRAP/SYSTEMUTILS - in tryGetProperty method catch block");
      return null;
    }
  }
}

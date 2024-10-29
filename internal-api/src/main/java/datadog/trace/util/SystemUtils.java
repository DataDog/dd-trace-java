package datadog.trace.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SystemUtils {
  private static final Logger log = LoggerFactory.getLogger(SystemUtils.class);
  // to be templatized with the type of thing we wanted to access and the key
  private static final String logMessageOnSecurityError =
      "The Java Security Manager prevented the Datadog Tracer from accessing the {} '{}'. "
          + "Consider granting AllPermission to the dd-java-agent jar.";

  private SystemUtils() {}

  public static String tryGetEnv(String envVar) {
    return getEnvOrDefault(envVar, null);
  }

  public static String getEnvOrDefault(String envVar, String defaultValue) {
    try {
      return System.getenv(envVar);
    } catch (SecurityException e) {
      log.warn(logMessageOnSecurityError, "environment variable", envVar, e);
      return defaultValue;
    }
  }

  public static String tryGetProperty(String property) {
    return getPropertyOrDefault(property, null);
  }

  public static String getPropertyOrDefault(String property, String defaultValue) {
    try {
      return System.getProperty(property, defaultValue);
    } catch (SecurityException e) {
      log.warn(logMessageOnSecurityError, "system property", property, e);
      return defaultValue;
    }
  }
}

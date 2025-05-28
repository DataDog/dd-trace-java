package datadog.environment;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Safely queries environment variables. */
public final class EnvironmentVariables {
  private EnvironmentVariables() {}

  public static @Nullable String get(String envVar) {
    return getOrDefault(envVar, null);
  }

  public static String getOrDefault(@Nonnull String envVar, String defaultValue) {
    try {
      return System.getenv(envVar);
    } catch (SecurityException e) {
      return defaultValue;
    }
  }
}

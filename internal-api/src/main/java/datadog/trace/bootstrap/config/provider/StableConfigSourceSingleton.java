package datadog.trace.bootstrap.config.provider;

import datadog.trace.api.ConfigOrigin;

public class StableConfigSourceSingleton {
  private static final StableConfigSource USER =
      new StableConfigSource(
          StableConfigSource.USER_STABLE_CONFIG_PATH, ConfigOrigin.USER_STABLE_CONFIG);
  private static final StableConfigSource MANAGED =
      new StableConfigSource(
          StableConfigSource.MANAGED_STABLE_CONFIG_PATH, ConfigOrigin.MANAGED_STABLE_CONFIG);

  // Private constructor to prevent instantiation
  private StableConfigSourceSingleton() {}

  public static StableConfigSource getUser() {
    return USER;
  }

  public static StableConfigSource getManaged() {
    return MANAGED;
  }
}

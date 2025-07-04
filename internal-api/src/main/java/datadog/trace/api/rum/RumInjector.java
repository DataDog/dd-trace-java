package datadog.trace.api.rum;

import datadog.trace.api.Config;

public final class RumInjector {
  private static volatile boolean initialized = false;
  private static volatile boolean enabled;
  private static volatile String snippet;

  /**
   * Check whether RUM injection is enabled and ready to inject.
   *
   * @return {@code true} if enabled, {@code otherwise}.
   */
  public static boolean isEnabled() {
    if (!initialized) {
      Config config = Config.get();
      boolean rumEnabled = config.isRumEnabled();
      RumInjectorConfig injectorConfig = config.getRumInjectorConfig();
      if (rumEnabled && injectorConfig != null) {
        enabled = true;
        snippet = injectorConfig.getSnippet();
      } else {
        enabled = false;
        snippet = null;
      }
      initialized = true;
    }
    return enabled;
  }

  /**
   * Get the HTML snippet to inject RUM SDK
   *
   * @return The HTML snippet to inject, {@code null} if RUM injection is disabled to inject.
   */
  public static String getSnippet() {
    return snippet;
  }
}

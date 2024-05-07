package datadog.trace.api.iast;

import datadog.trace.api.Config;
import datadog.trace.api.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class IastEnabledChecks {

  private static final Logger LOGGER = LoggerFactory.getLogger(IastEnabledChecks.class);

  private IastEnabledChecks() {}

  public static boolean isMajorJavaVersionAtLeast(final String version) {
    try {
      return Platform.isJavaVersionAtLeast(Integer.parseInt(version));
    } catch (final Exception e) {
      LOGGER.error(
          "Error checking major java version {}, expect some call sites to be disabled",
          version,
          e);
      return false;
    }
  }

  public static boolean isFullDetection() {
    return Config.get().getIastDetectionMode() == IastDetectionMode.FULL;
  }
}

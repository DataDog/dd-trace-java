package datadog.trace.api.openfeature;

import datadog.trace.api.featureflag.config.FeatureFlaggingConfig;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import java.util.function.BooleanSupplier;

/**
 * Reads {@code DD_FEATURE_FLAGS_EXPOSURES_DATADOG_LOGGING_ENABLED} with full {@link ConfigProvider}
 * precedence. When false, exposures are not sent to Datadog, but the exposure cache and customer
 * {@link ExposureHook}s keep working. On by default.
 */
final class DatadogExposureLoggingGate {

  private DatadogExposureLoggingGate() {}

  static boolean isEnabled() {
    return isEnabled(
        () ->
            ConfigProvider.getInstance()
                .getBoolean(
                    FeatureFlaggingConfig.FEATURE_FLAGS_EXPOSURES_DATADOG_LOGGING_ENABLED, true));
  }

  static boolean isEnabled(final BooleanSupplier setting) {
    try {
      return setting.getAsBoolean();
    } catch (final Throwable t) {
      // Keep the default rather than silently stop sending exposures.
      return true;
    }
  }
}

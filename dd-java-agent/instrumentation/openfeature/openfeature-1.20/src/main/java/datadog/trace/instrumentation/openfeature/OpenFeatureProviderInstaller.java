package datadog.trace.instrumentation.openfeature;

import static datadog.trace.api.featureflag.config.FeatureFlaggingConfig.EXPERIMENTAL_FLAGGING_PROVIDER_ENABLED;
import static datadog.trace.api.featureflag.config.FeatureFlaggingConfig.FEATURE_FLAGS_CONFIGURATION_SOURCE;
import static datadog.trace.api.featureflag.config.FeatureFlaggingConfig.FEATURE_FLAGS_ENABLED;

import datadog.trace.api.openfeature.Provider;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import dev.openfeature.sdk.FeatureProvider;
import dev.openfeature.sdk.NoOpProvider;
import dev.openfeature.sdk.OpenFeatureAPI;

public final class OpenFeatureProviderInstaller {

  private static final Object INSTALLATION_LOCK = new Object();
  private static boolean installationComplete;

  private OpenFeatureProviderInstaller() {}

  public static void install(final OpenFeatureAPI api) {
    synchronized (INSTALLATION_LOCK) {
      if (installationComplete || api == null) {
        return;
      }

      final ConfigProvider config = ConfigProvider.getInstance();
      if (!config.isSet(FEATURE_FLAGS_ENABLED)
          && !config.isSet(FEATURE_FLAGS_CONFIGURATION_SOURCE)
          && !config.isSet(EXPERIMENTAL_FLAGGING_PROVIDER_ENABLED)) {
        installationComplete = true;
        return;
      }

      final FeatureProvider currentProvider = api.getProvider();
      if (currentProvider == null || currentProvider.getClass() != NoOpProvider.class) {
        installationComplete = true;
        return;
      }

      api.setProvider(new Provider());
      installationComplete = true;
    }
  }
}

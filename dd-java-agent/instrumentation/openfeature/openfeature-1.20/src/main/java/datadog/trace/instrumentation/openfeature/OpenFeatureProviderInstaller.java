package datadog.trace.instrumentation.openfeature;

import datadog.trace.api.featureflag.FeatureFlaggingGateway;
import datadog.trace.api.openfeature.Provider;
import dev.openfeature.sdk.FeatureProvider;
import dev.openfeature.sdk.NoOpProvider;
import dev.openfeature.sdk.OpenFeatureAPI;

public final class OpenFeatureProviderInstaller {

  private static boolean installationComplete;

  private OpenFeatureProviderInstaller() {}

  public static synchronized void install(final OpenFeatureAPI api) {
    if (installationComplete
        || api == null
        || !FeatureFlaggingGateway.isProviderInjectionEnabled()) {
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

package com.datadog.featureflag;

import static java.util.Objects.requireNonNull;

import datadog.remoteconfig.Capabilities;
import datadog.remoteconfig.ConfigurationChangesTypedListener;
import datadog.remoteconfig.ConfigurationPoller;
import datadog.remoteconfig.PollingRateHinter;
import datadog.remoteconfig.Product;
import datadog.trace.api.featureflag.FeatureFlaggingGateway;
import datadog.trace.api.featureflag.ufc.v1.ServerConfiguration;
import javax.annotation.Nullable;

public class RemoteConfigServiceImpl
    implements ConfigurationSourceService, ConfigurationChangesTypedListener<ServerConfiguration> {

  private final ConfigurationPoller configurationPoller;

  public RemoteConfigServiceImpl(final ConfigurationPoller configurationPoller) {
    this.configurationPoller = requireNonNull(configurationPoller);
  }

  @Override
  public void init() {
    configurationPoller.addCapabilities(Capabilities.CAPABILITY_FFE_FLAG_CONFIGURATION_RULES);
    configurationPoller.addListener(
        Product.FFE_FLAGS, UniversalFlagConfigParser.INSTANCE::deserialize, this);
    configurationPoller.start();
  }

  @Override
  public void close() {
    configurationPoller.removeCapabilities(Capabilities.CAPABILITY_FFE_FLAG_CONFIGURATION_RULES);
    configurationPoller.removeListeners(Product.FFE_FLAGS);
    configurationPoller.stop();
  }

  @Override
  public void accept(
      final String configKey,
      @Nullable final ServerConfiguration configuration,
      final PollingRateHinter pollingRateHinter) {
    FeatureFlaggingGateway.dispatch(configuration);
  }
}

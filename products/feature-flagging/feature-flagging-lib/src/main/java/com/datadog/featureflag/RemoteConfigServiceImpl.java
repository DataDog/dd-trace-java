package com.datadog.featureflag;

import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.remoteconfig.Capabilities;
import datadog.remoteconfig.ConfigurationChangesTypedListener;
import datadog.remoteconfig.ConfigurationPoller;
import datadog.remoteconfig.PollingRateHinter;
import datadog.remoteconfig.Product;
import datadog.trace.api.Config;
import datadog.trace.api.featureflag.FeatureFlaggingGateway;
import datadog.trace.api.featureflag.ufc.v1.ServerConfiguration;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RemoteConfigServiceImpl
    implements ConfigurationSourceService, ConfigurationChangesTypedListener<ServerConfiguration> {

  private static final Logger LOGGER = LoggerFactory.getLogger(RemoteConfigServiceImpl.class);

  private final ConfigurationPoller configurationPoller;

  public RemoteConfigServiceImpl(final SharedCommunicationObjects sco, final Config config) {
    configurationPoller = sco.configurationPoller(config);
  }

  @Override
  public void init() {
    LOGGER.debug(
        "Feature Flagging starting remote_config configuration source (Agent Remote Configuration)");
    configurationPoller.addCapabilities(Capabilities.CAPABILITY_FFE_FLAG_CONFIGURATION_RULES);
    configurationPoller.addListener(Product.FFE_FLAGS, UniversalFlagConfigParser.INSTANCE, this);
    configurationPoller.start();
  }

  @Override
  public void close() {
    configurationPoller.removeCapabilities(Capabilities.CAPABILITY_FFE_FLAG_CONFIGURATION_RULES);
    configurationPoller.removeListeners(Product.FFE_FLAGS);
    configurationPoller.stop();
    LOGGER.debug("Feature Flagging remote_config configuration source stopped");
  }

  @Override
  public void accept(
      final String configKey,
      @Nullable final ServerConfiguration configuration,
      final PollingRateHinter pollingRateHinter) {
    FeatureFlaggingGateway.dispatch(configuration);
    if (configuration == null) {
      LOGGER.debug("Feature Flagging remote_config configuration removed");
    } else if (LOGGER.isDebugEnabled()) {
      LOGGER.debug(
          "Feature Flagging remote_config configuration applied: key={}, flags={}, configurationEnv={}, createdAt={}, invalidFlags={}",
          configKey,
          // A payload without a flags member is not an empty configuration.
          configuration.flags == null ? "missing" : configuration.flags.size(),
          configuration.environment == null ? null : configuration.environment.name,
          configuration.createdAt,
          configuration.invalidFlags == null ? 0 : configuration.invalidFlags.size());
    }
  }
}

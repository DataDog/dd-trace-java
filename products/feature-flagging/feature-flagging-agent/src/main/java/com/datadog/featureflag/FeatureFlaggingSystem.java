package com.datadog.featureflag;

import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.trace.api.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FeatureFlaggingSystem {

  private static final Logger LOGGER = LoggerFactory.getLogger(FeatureFlaggingSystem.class);
  private static final String SOURCE_AGENTLESS = "agentless";
  private static final String SOURCE_REMOTE_CONFIG = "remote_config";
  private static final String SOURCE_OFFLINE = "offline";

  private static volatile ConfigurationSourceService CONFIG_SERVICE;
  private static volatile ExposureWriter EXPOSURE_WRITER;

  private FeatureFlaggingSystem() {}

  public static void start(final SharedCommunicationObjects sco) {
    LOGGER.debug("Feature Flagging system starting");
    final Config config = Config.get();
    CONFIG_SERVICE = createConfigurationSourceService(sco, config);

    if (CONFIG_SERVICE != null) {
      CONFIG_SERVICE.init();
    }

    EXPOSURE_WRITER = new ExposureWriterImpl(sco, config);
    EXPOSURE_WRITER.init();

    LOGGER.debug("Feature Flagging system started");
  }

  static ConfigurationSourceService createConfigurationSourceService(
      final SharedCommunicationObjects sco, final Config config) {
    final String configurationSource = config.getFlaggingConfigurationSource();

    if (SOURCE_REMOTE_CONFIG.equals(configurationSource)) {
      if (!config.isRemoteConfigEnabled()) {
        throw new IllegalStateException("Feature Flagging system started without RC");
      }
      return new RemoteConfigServiceImpl(sco, config);
    } else if (SOURCE_AGENTLESS.equals(configurationSource)) {
      return new UfcHttpConfigService(config);
    } else if (SOURCE_OFFLINE.equals(configurationSource)) {
      LOGGER.debug(
          "Feature Flagging offline configuration source selected; no config service started");
      return null;
    } else {
      throw new IllegalArgumentException(
          "Unsupported Feature Flagging configuration source: " + configurationSource);
    }
  }

  public static void stop() {
    if (EXPOSURE_WRITER != null) {
      EXPOSURE_WRITER.close();
      EXPOSURE_WRITER = null;
    }
    if (CONFIG_SERVICE != null) {
      CONFIG_SERVICE.close();
      CONFIG_SERVICE = null;
    }
    LOGGER.debug("Feature Flagging system stopped");
  }
}

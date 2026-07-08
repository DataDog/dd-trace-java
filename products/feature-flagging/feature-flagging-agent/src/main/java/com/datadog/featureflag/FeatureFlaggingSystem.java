package com.datadog.featureflag;

import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.trace.api.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FeatureFlaggingSystem {

  private static final Logger LOGGER = LoggerFactory.getLogger(FeatureFlaggingSystem.class);
  private static final String SOURCE_CDN = "cdn";
  private static final String SOURCE_REMOTE_CONFIG = "remote_config";
  private static final String SOURCE_OFFLINE = "offline";

  private static volatile RemoteConfigService CONFIG_SERVICE;
  private static volatile ExposureWriter EXPOSURE_WRITER;
  private static final ConfigServiceFactory DEFAULT_CDN_CONFIG_SERVICE_FACTORY =
      (sco, config) -> new CdnConfigService(config);
  private static final ConfigServiceFactory DEFAULT_REMOTE_CONFIG_SERVICE_FACTORY =
      RemoteConfigServiceImpl::new;
  private static volatile ConfigServiceFactory CDN_CONFIG_SERVICE_FACTORY =
      DEFAULT_CDN_CONFIG_SERVICE_FACTORY;
  private static volatile ConfigServiceFactory REMOTE_CONFIG_SERVICE_FACTORY =
      DEFAULT_REMOTE_CONFIG_SERVICE_FACTORY;

  private FeatureFlaggingSystem() {}

  public static void start(final SharedCommunicationObjects sco) {
    LOGGER.debug("Feature Flagging system starting");
    final Config config = Config.get();
    final String configurationSource = config.getFlaggingConfigurationSource();

    if (SOURCE_REMOTE_CONFIG.equals(configurationSource)) {
      if (!config.isRemoteConfigEnabled()) {
        throw new IllegalStateException("Feature Flagging system started without RC");
      }
      CONFIG_SERVICE = REMOTE_CONFIG_SERVICE_FACTORY.create(sco, config);
      CONFIG_SERVICE.init();
    } else if (SOURCE_CDN.equals(configurationSource)) {
      CONFIG_SERVICE = CDN_CONFIG_SERVICE_FACTORY.create(sco, config);
      CONFIG_SERVICE.init();
    } else if (SOURCE_OFFLINE.equals(configurationSource)) {
      LOGGER.debug(
          "Feature Flagging offline configuration source selected; no config service started");
    } else {
      throw new IllegalArgumentException(
          "Unsupported Feature Flagging configuration source: " + configurationSource);
    }

    EXPOSURE_WRITER = new ExposureWriterImpl(sco, config);
    EXPOSURE_WRITER.init();

    LOGGER.debug("Feature Flagging system started");
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

  public static void setServiceFactoriesForTest(
      final ConfigServiceFactory cdnConfigServiceFactory,
      final ConfigServiceFactory remoteConfigServiceFactory) {
    CDN_CONFIG_SERVICE_FACTORY = cdnConfigServiceFactory;
    REMOTE_CONFIG_SERVICE_FACTORY = remoteConfigServiceFactory;
  }

  public static void resetServiceFactoriesForTest() {
    CDN_CONFIG_SERVICE_FACTORY = DEFAULT_CDN_CONFIG_SERVICE_FACTORY;
    REMOTE_CONFIG_SERVICE_FACTORY = DEFAULT_REMOTE_CONFIG_SERVICE_FACTORY;
  }

  public interface ConfigServiceFactory {
    RemoteConfigService create(SharedCommunicationObjects sco, Config config);
  }
}

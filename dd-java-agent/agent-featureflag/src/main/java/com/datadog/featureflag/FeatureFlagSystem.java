package com.datadog.featureflag;

import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.remoteconfig.ConfigurationPoller;
import datadog.trace.api.Config;
import datadog.trace.api.featureflag.FeatureFlag;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FeatureFlagSystem {

  private static final Logger LOGGER = LoggerFactory.getLogger(FeatureFlagSystem.class);

  private static FeatureFlagRemoteConfigService CONFIG_SERVICE;
  private static ExposureWriter EXPOSURE_WRITER;

  public static void start(final SharedCommunicationObjects sco) {
    LOGGER.debug("Feature Flag system starting");
    final Config config = Config.get();

    final ConfigurationPoller poller = sco.configurationPoller(config);
    if (poller == null) {
      throw new IllegalStateException(
          "Feature flags evaluation won't be started, remote config is likely disabled");
    }

    EXPOSURE_WRITER = new ExposureWriterImpl(1, 1, TimeUnit.SECONDS, sco, config);
    final FeatureFlagEvaluatorImpl evaluator = new FeatureFlagEvaluatorImpl();
    FeatureFlag.EVALUATOR = new ExposureEvaluatorAdapter(EXPOSURE_WRITER, evaluator);

    CONFIG_SERVICE = new FeatureFlagRemoteConfigServiceImpl(poller);
    CONFIG_SERVICE.addConsumer(evaluator);
    CONFIG_SERVICE.init();

    LOGGER.debug("Feature Flag system started");
  }

  public static void stop() {
    EXPOSURE_WRITER.close();
    CONFIG_SERVICE.close();
    LOGGER.debug("Feature Flag system stopped");
  }
}

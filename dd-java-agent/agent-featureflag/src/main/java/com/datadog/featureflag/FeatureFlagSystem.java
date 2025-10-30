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
  private static final int EXPOSURE_WRITER_CAPACITY = 100_000;
  private static final int EXPOSURE_WRITER_FLUSH_INTERVAL_SECONDS = 1;

  private static volatile FeatureFlagRemoteConfigService CONFIG_SERVICE;
  private static volatile ExposureWriter EXPOSURE_WRITER;

  public static void start(final SharedCommunicationObjects sco) {
    LOGGER.debug("Feature Flag system starting");
    final Config config = Config.get();

    final ConfigurationPoller poller = sco.configurationPoller(config);
    if (poller == null) {
      throw new IllegalStateException(
          "Feature flags evaluation won't be started, remote config is likely disabled");
    }

    EXPOSURE_WRITER =
        new ExposureWriterImpl(
            EXPOSURE_WRITER_CAPACITY,
            EXPOSURE_WRITER_FLUSH_INTERVAL_SECONDS,
            TimeUnit.SECONDS,
            sco.agentUrl,
            config);
    final FeatureFlagEvaluatorImpl evaluator = new FeatureFlagEvaluatorImpl();
    FeatureFlag.addConfigListener(evaluator);
    FeatureFlag.init(new ExposureWriterEvaluatorAdapter(EXPOSURE_WRITER, evaluator));

    CONFIG_SERVICE = new FeatureFlagRemoteConfigServiceImpl(poller);
    CONFIG_SERVICE.init();

    LOGGER.debug("Feature Flag system started");
  }

  public static void stop() {
    EXPOSURE_WRITER.close();
    CONFIG_SERVICE.close();
    LOGGER.debug("Feature Flag system stopped");
  }
}

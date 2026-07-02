package com.datadog.featureflag;

import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.trace.api.Config;
import datadog.trace.api.config.FeatureFlaggingConfig;
import datadog.trace.api.featureflag.flagevaluation.FlagEvaluationWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FeatureFlaggingSystem {

  private static final Logger LOGGER = LoggerFactory.getLogger(FeatureFlaggingSystem.class);

  private static volatile RemoteConfigService CONFIG_SERVICE;
  private static volatile ExposureWriter EXPOSURE_WRITER;
  private static volatile FlagEvaluationWriter FLAG_EVAL_WRITER;

  private FeatureFlaggingSystem() {}

  public static void start(final SharedCommunicationObjects sco) {
    LOGGER.debug("Feature Flagging system starting");
    final Config config = Config.get();

    if (!config.isRemoteConfigEnabled()) {
      throw new IllegalStateException("Feature Flagging system started without RC");
    }
    CONFIG_SERVICE = new RemoteConfigServiceImpl(sco, config);
    CONFIG_SERVICE.init();

    EXPOSURE_WRITER = new ExposureWriterImpl(sco, config);
    EXPOSURE_WRITER.init();

    final boolean evalCountsEnabled =
        config
            .configProvider()
            .getBoolean(FeatureFlaggingConfig.FLAGGING_EVALUATION_COUNTS_ENABLED, true);
    if (evalCountsEnabled) {
      final FlagEvaluationWriterImpl evalWriter = new FlagEvaluationWriterImpl(sco, config);
      evalWriter.start();
      FLAG_EVAL_WRITER = evalWriter;
      LOGGER.debug("Flag evaluation EVP writer started");
    } else {
      LOGGER.debug(
          "Flag evaluation EVP writer disabled ({}=false)",
          FeatureFlaggingConfig.FLAGGING_EVALUATION_COUNTS_ENABLED);
    }

    LOGGER.debug("Feature Flagging system started");
  }

  public static void stop() {
    if (FLAG_EVAL_WRITER != null) {
      FLAG_EVAL_WRITER.close();
      FLAG_EVAL_WRITER = null;
    }
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

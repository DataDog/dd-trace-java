package com.datadog.featureflag;

import datadog.communication.DirectIntakeApiFactory;
import datadog.communication.http.OkHttpUtils;
import datadog.trace.api.Config;
import datadog.trace.api.intake.Intake;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Standalone composition. Never discovers or falls back to a local agent. */
final class DirectEventPipelines {
  private static final Logger LOGGER = LoggerFactory.getLogger(DirectEventPipelines.class);
  private final Config config;
  private final DirectIntakeApiFactory factory;

  DirectEventPipelines(Config config) {
    this.config = config;
    long timeout =
        config.isCiVisibilityEnabled()
            ? config.getCiVisibilityBackendApiTimeoutMillis()
            : TimeUnit.SECONDS.toMillis(config.getAgentTimeout());
    this.factory =
        new DirectIntakeApiFactory(
            config,
            OkHttpUtils.buildHttpClient(
                config.isForceClearTextHttpForIntakeClient(), null, null, timeout));
  }

  ExposureWriter exposures() {
    return new ExposurePipeline(
        1 << 16,
        1,
        TimeUnit.SECONDS,
        transport(true),
        FeatureFlagEvpContext.from(config),
        RuntimeServices.STANDALONE);
  }

  FlagEvaluationPipeline evaluations() {
    return new FlagEvaluationPipeline(
        1 << 12,
        10,
        TimeUnit.SECONDS,
        transport(false),
        FeatureFlagEvpContext.from(config),
        RuntimeServices.STANDALONE);
  }

  private Supplier<EventTransport> transport(boolean compression) {
    return BackendEventTransport.adapt(
        () -> {
          if (config.getApiKey() == null || config.getApiKey().isEmpty()) {
            LOGGER.warn("Feature Flags event delivery requires a direct intake API key");
            return null;
          }
          return factory.create(Intake.EVENT_PLATFORM, compression, false);
        });
  }
}

package com.datadog.featureflag;

import datadog.communication.BackendApi;
import datadog.communication.BackendApiFactory;
import datadog.communication.DirectIntakeApiFactory;
import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.communication.http.OkHttpUtils;
import datadog.trace.api.Config;
import datadog.trace.api.intake.Intake;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Standalone events use the requested source's transport, without switching delivery modes. */
final class StandaloneEventPipelines {
  private static final Logger LOGGER = LoggerFactory.getLogger(StandaloneEventPipelines.class);
  private final Config config;
  private final Function<Boolean, BackendApi> backend;

  private StandaloneEventPipelines(Config config, Function<Boolean, BackendApi> backend) {
    this.config = config;
    this.backend = backend;
  }

  static StandaloneEventPipelines direct(Config config) {
    long timeout =
        config.isCiVisibilityEnabled()
            ? config.getCiVisibilityBackendApiTimeoutMillis()
            : TimeUnit.SECONDS.toMillis(config.getAgentTimeout());
    final DirectIntakeApiFactory factory =
        new DirectIntakeApiFactory(
            config,
            OkHttpUtils.buildHttpClient(
                config.isForceClearTextHttpForIntakeClient(), null, null, timeout));
    return new StandaloneEventPipelines(
        config,
        compression -> {
          if (config.getApiKey() == null || config.getApiKey().isEmpty()) {
            LOGGER.warn("Feature Flags event delivery requires a direct intake API key");
            return null;
          }
          return factory.create(Intake.EVENT_PLATFORM, compression, false);
        });
  }

  static StandaloneEventPipelines remoteConfig(Config config, SharedCommunicationObjects sco) {
    final BackendApiFactory factory = new BackendApiFactory(config, sco);
    return new StandaloneEventPipelines(
        config, compression -> factory.createEvpProxyApi(Intake.EVENT_PLATFORM, compression));
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
    return BackendEventTransport.adapt(() -> backend.apply(compression));
  }
}

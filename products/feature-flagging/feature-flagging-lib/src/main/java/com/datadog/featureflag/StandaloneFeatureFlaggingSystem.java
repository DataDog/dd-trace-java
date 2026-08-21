package com.datadog.featureflag;

import static datadog.trace.api.featureflag.config.FeatureFlaggingConfig.CONFIGURATION_SOURCE_AGENTLESS;
import static datadog.trace.api.featureflag.config.FeatureFlaggingConfig.FEATURE_FLAGS_CONFIGURATION_SOURCE;

import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.trace.api.Config;
import datadog.trace.api.featureflag.FeatureFlaggingGateway;
import datadog.trace.api.featureflag.FeatureFlaggingGateway.RuntimeMode;
import datadog.trace.api.featureflag.config.FeatureFlaggingConfig;
import datadog.trace.api.featureflag.flagevaluation.FlagEvaluationWriter;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns agentless configuration and event delivery when {@code dd-openfeature} runs without an
 * agent.
 */
public final class StandaloneFeatureFlaggingSystem {

  @FunctionalInterface
  interface SystemInitializer {
    void initialize(Config config);
  }

  private static final Logger LOGGER =
      LoggerFactory.getLogger(StandaloneFeatureFlaggingSystem.class);

  private static volatile ConfigurationSourceService CONFIG_SERVICE;
  private static volatile ExposureWriter EXPOSURE_WRITER;
  private static volatile FlagEvaluationWriter FLAG_EVAL_WRITER;
  private static volatile boolean STARTED;

  private StandaloneFeatureFlaggingSystem() {}

  /** Starts standalone delivery when the configured source is {@code agentless}. */
  public static boolean start() {
    return start(StandaloneFeatureFlaggingSystem::initializeSystem);
  }

  static synchronized boolean start(final SystemInitializer systemInitializer) {
    if (STARTED) {
      return FeatureFlaggingGateway.activeRuntime() == RuntimeMode.STANDALONE;
    }

    final Config config = Config.get();
    final String explicitSource =
        config.configProvider().getString(FEATURE_FLAGS_CONFIGURATION_SOURCE);
    if (explicitSource == null
        || !CONFIGURATION_SOURCE_AGENTLESS.equalsIgnoreCase(explicitSource.trim())) {
      return false;
    }
    if (!FeatureFlaggingGateway.claimRuntime(RuntimeMode.STANDALONE)) {
      LOGGER.debug(
          "Standalone Feature Flagging runtime not started because {} already owns the subsystem",
          FeatureFlaggingGateway.activeRuntime());
      return false;
    }

    STARTED = true;
    try {
      systemInitializer.initialize(config);
      LOGGER.debug("Standalone Feature Flagging runtime started");
      return true;
    } catch (final RuntimeException | Error exception) {
      stop();
      throw exception;
    }
  }

  private static void initializeSystem(final Config config) {
    final SharedCommunicationObjects communicationObjects = new SharedCommunicationObjects();
    communicationObjects.createRemaining(config);
    final ConfigurationSourceService configService = new AgentlessConfigurationSource(config);
    final ExposureWriter exposureWriter =
        new ExposureWriterImpl(communicationObjects, config, false);
    initialize(configService, exposureWriter);

    final boolean evalCountsEnabled =
        config
            .configProvider()
            .getBoolean(FeatureFlaggingConfig.FLAGGING_EVALUATION_COUNTS_ENABLED, true);
    FeatureFlaggingGateway.setFlagEvaluationEnqueueEnabled(evalCountsEnabled);
    if (evalCountsEnabled) {
      final FlagEvaluationWriterImpl evalWriter =
          new FlagEvaluationWriterImpl(communicationObjects, config, false);
      FLAG_EVAL_WRITER = evalWriter;
      evalWriter.start();
    } else {
      FeatureFlaggingGateway.setFlagEvalWriter(null);
    }
  }

  static void initialize(
      final ConfigurationSourceService configService, final ExposureWriter exposureWriter) {
    try {
      configService.init();
      exposureWriter.init();
      CONFIG_SERVICE = configService;
      EXPOSURE_WRITER = exposureWriter;
    } catch (final RuntimeException | Error exception) {
      try {
        exposureWriter.close();
      } finally {
        configService.close();
      }
      throw exception;
    }
  }

  @SuppressFBWarnings(
      value = "USO_UNSAFE_STATIC_METHOD_SYNCHRONIZATION",
      justification = "The class is process-internal and its Class object does not escape.")
  public static synchronized boolean stop() {
    final boolean wasStarted = STARTED;
    FeatureFlaggingGateway.setFlagEvaluationEnqueueEnabled(false);
    FeatureFlaggingGateway.setFlagEvalWriter(null);
    final FlagEvaluationWriter flagEvalWriter = FLAG_EVAL_WRITER;
    final ExposureWriter exposureWriter = EXPOSURE_WRITER;
    final ConfigurationSourceService configService = CONFIG_SERVICE;
    STARTED = false;
    FLAG_EVAL_WRITER = null;
    EXPOSURE_WRITER = null;
    CONFIG_SERVICE = null;
    closeQuietly(flagEvalWriter);
    closeQuietly(exposureWriter);
    closeQuietly(configService);
    FeatureFlaggingGateway.releaseRuntime(RuntimeMode.STANDALONE);
    if (wasStarted) {
      LOGGER.debug("Standalone Feature Flagging runtime stopped");
    }
    return wasStarted;
  }

  private static void closeQuietly(final AutoCloseable resource) {
    if (resource != null) {
      try {
        resource.close();
      } catch (final Exception ignored) {
      }
    }
  }
}

package com.datadog.featureflag;

import static datadog.trace.api.featureflag.config.FeatureFlaggingConfig.CONFIGURATION_SOURCE_AGENTLESS;

import datadog.trace.api.Config;
import datadog.trace.api.featureflag.FeatureFlaggingGateway;
import datadog.trace.api.featureflag.FeatureFlaggingGateway.RuntimeMode;
import datadog.trace.api.featureflag.config.FeatureFlaggingConfig;
import datadog.trace.api.featureflag.flagevaluation.FlagEvaluationWriter;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.function.Supplier;
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
  private static int consumers;
  private static long generation;

  private StandaloneFeatureFlaggingSystem() {}

  /** Starts standalone delivery when the configured source is {@code agentless}. */
  public static boolean start() {
    return start(StandaloneFeatureFlaggingSystem::initializeSystem);
  }

  /** Acquires one consumer; only the final consumer shuts down standalone delivery. */
  public static synchronized AutoCloseable acquire() {
    return acquire(StandaloneFeatureFlaggingSystem::initializeSystem);
  }

  static synchronized AutoCloseable acquire(final SystemInitializer initializer) {
    if (!start(initializer)) {
      return null;
    }
    consumers++;
    final long acquiredGeneration = generation;
    return new AutoCloseable() {
      private boolean closed;

      @Override
      public void close() {
        synchronized (StandaloneFeatureFlaggingSystem.class) {
          if (closed) {
            return;
          }
          closed = true;
          if (acquiredGeneration == generation && consumers > 0 && --consumers == 0) {
            stop();
          }
        }
      }
    };
  }

  static synchronized boolean start(final SystemInitializer systemInitializer) {
    if (STARTED) {
      return FeatureFlaggingGateway.activeRuntime() == RuntimeMode.STANDALONE;
    }

    final Config config = Config.get();
    final FeatureFlaggingConfig.Resolution resolved =
        FeatureFlaggingConfig.resolveConfiguration(
            config.configProvider().getBoolean(FeatureFlaggingConfig.FEATURE_FLAGS_ENABLED),
            config
                .configProvider()
                .getString(FeatureFlaggingConfig.FEATURE_FLAGS_CONFIGURATION_SOURCE),
            config
                .configProvider()
                .getBoolean(FeatureFlaggingConfig.EXPERIMENTAL_FLAGGING_PROVIDER_ENABLED));
    if (!resolved.isEnabled() || !CONFIGURATION_SOURCE_AGENTLESS.equals(resolved.getSource())) {
      return false;
    }
    if (!FeatureFlaggingGateway.claimRuntime(RuntimeMode.STANDALONE)) {
      LOGGER.debug(
          "Standalone Feature Flagging runtime not started because {} already owns the subsystem",
          FeatureFlaggingGateway.activeRuntime());
      return false;
    }

    STARTED = true;
    generation++;
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
    DefaultRuntime.initialize(config);
  }

  static void initializeSystem(
      final ConfigurationSourceService configService,
      final ExposureWriter exposureWriter,
      final Supplier<FlagEvaluationWriter> evalWriterFactory,
      final boolean evalCountsEnabled) {
    initialize(configService, exposureWriter);

    FeatureFlaggingGateway.setFlagEvaluationEnqueueEnabled(evalCountsEnabled);
    if (evalCountsEnabled) {
      final FlagEvaluationWriter evalWriter = evalWriterFactory.get();
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
    consumers = 0;
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

  /** Composition root for the concrete standalone transports, validated by deployment tests. */
  private static final class DefaultRuntime {
    private static void initialize(final Config config) {
      final DirectEventPipelines events = new DirectEventPipelines(config);
      final ConfigurationSourceService configService =
          new AgentlessConfigurationSource(config, RuntimeServices.STANDALONE);
      final ExposureWriter exposureWriter = events.exposures();
      final boolean evalCountsEnabled =
          config
              .configProvider()
              .getBoolean(FeatureFlaggingConfig.FLAGGING_EVALUATION_COUNTS_ENABLED, true);
      initializeSystem(configService, exposureWriter, events::evaluations, evalCountsEnabled);
    }
  }
}

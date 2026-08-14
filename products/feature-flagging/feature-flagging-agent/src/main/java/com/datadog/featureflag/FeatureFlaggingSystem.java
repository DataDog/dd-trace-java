package com.datadog.featureflag;

import static datadog.trace.api.featureflag.config.FeatureFlaggingConfig.CONFIGURATION_SOURCE_AGENTLESS;
import static datadog.trace.api.featureflag.config.FeatureFlaggingConfig.CONFIGURATION_SOURCE_REMOTE_CONFIG;

import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.trace.api.Config;
import datadog.trace.api.featureflag.FeatureFlaggingGateway;
import datadog.trace.api.featureflag.config.FeatureFlaggingConfig;
import datadog.trace.api.featureflag.flagevaluation.FlagEvaluationWriter;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FeatureFlaggingSystem {

  @FunctionalInterface
  interface ExposureWriterFactory {
    ExposureWriter create(SharedCommunicationObjects sco, Config config);
  }

  private static final Logger LOGGER = LoggerFactory.getLogger(FeatureFlaggingSystem.class);

  private static volatile ConfigurationSourceService CONFIG_SERVICE;
  private static volatile ExposureWriter EXPOSURE_WRITER;
  private static volatile FlagEvaluationWriter FLAG_EVAL_WRITER;
  private static volatile SpanEnrichmentWriter SPAN_ENRICHMENT_WRITER;
  private static volatile FeatureFlaggingGateway.ActivationListener ACTIVATION_LISTENER;
  private static volatile boolean STARTED;

  private FeatureFlaggingSystem() {}

  public static void start(final SharedCommunicationObjects sco) {
    start(sco, ExposureWriterImpl::new);
  }

  @SuppressFBWarnings(
      value = "USO_UNSAFE_STATIC_METHOD_SYNCHRONIZATION",
      justification =
          "Agent-internal class; Class object does not escape to app code and lock only guards the subsystem lifecycle.")
  static synchronized void start(
      final SharedCommunicationObjects sco, final ExposureWriterFactory exposureWriterFactory) {
    if (STARTED) {
      LOGGER.debug("Feature Flagging system already started");
      return;
    }
    LOGGER.debug("Feature Flagging system starting");
    final Config config = Config.get();
    STARTED = true;

    if (!config.isFeatureFlaggingProviderEnabled()) {
      LOGGER.debug("Feature Flagging system disabled");
      return;
    }

    if (CONFIGURATION_SOURCE_AGENTLESS.equals(config.getFeatureFlaggingConfigurationSource())) {
      final FeatureFlaggingGateway.ActivationListener activationListener =
          () -> activateAgentless(sco, config);
      ACTIVATION_LISTENER = activationListener;
      FeatureFlaggingGateway.addActivationListener(activationListener);
      try {
        initializeExposureWriter(sco, config, exposureWriterFactory);
      } catch (final RuntimeException | Error e) {
        stop();
        throw e;
      }
      LOGGER.debug("Feature Flagging system awaiting application provider activation");
      return;
    }

    initializeOrRollBack(sco, config);
  }

  private static synchronized void activateAgentless(
      final SharedCommunicationObjects sco, final Config config) {
    final FeatureFlaggingGateway.ActivationListener activationListener = ACTIVATION_LISTENER;
    if (!STARTED || activationListener == null) {
      return;
    }
    ACTIVATION_LISTENER = null;
    FeatureFlaggingGateway.removeActivationListener(activationListener);
    initializeOrRollBack(sco, config);
  }

  // Any failure leaves the subsystem fully stopped: stop() releases whatever initializeSystem
  // managed to publish before it threw, so a later start() begins from a clean state.
  private static void initializeOrRollBack(
      final SharedCommunicationObjects sco, final Config config) {
    try {
      initializeSystem(sco, config);
    } catch (final RuntimeException | Error e) {
      stop();
      throw e;
    }
  }

  private static void initializeSystem(final SharedCommunicationObjects sco, final Config config) {
    final ConfigurationSourceService configService = createConfigurationSourceService(sco, config);
    if (configService == null) {
      LOGGER.debug("Feature Flagging system disabled by unsupported configuration source");
      return;
    }
    ExposureWriter exposureWriter = EXPOSURE_WRITER;
    if (exposureWriter instanceof ExposureWriterImpl
        && !((ExposureWriterImpl) exposureWriter).isSerializerThreadAlive()) {
      closeQuietly(exposureWriter);
      EXPOSURE_WRITER = null;
      exposureWriter = null;
    }
    if (exposureWriter == null) {
      final ExposureWriter newExposureWriter = new ExposureWriterImpl(sco, config);
      initialize(configService, newExposureWriter);
    } else {
      initializeConfigurationSource(configService, exposureWriter);
    }

    final boolean evalCountsEnabled =
        config
            .configProvider()
            .getBoolean(FeatureFlaggingConfig.FLAGGING_EVALUATION_COUNTS_ENABLED, true);
    FeatureFlaggingGateway.setFlagEvaluationEnqueueEnabled(evalCountsEnabled);
    if (evalCountsEnabled) {
      final FlagEvaluationWriterImpl evalWriter = new FlagEvaluationWriterImpl(sco, config);
      // Publish before start() so a failed start is still reachable by the rollback in stop().
      FLAG_EVAL_WRITER = evalWriter;
      evalWriter.start();
      LOGGER.debug("Flag evaluation EVP writer started");
    } else {
      FeatureFlaggingGateway.setFlagEvalWriter(null);
      LOGGER.debug(
          "Flag evaluation EVP writer disabled ({}=false)",
          FeatureFlaggingConfig.FLAGGING_EVALUATION_COUNTS_ENABLED);
    }

    // APM span enrichment: agent-side listener for flag-evaluation seam events. Uses the process-
    // wide singleton so a subsystem restart reuses the one already-registered trace interceptor
    // (which the tracer cannot remove) instead of registering a second, rejected one. Cheap: it
    // only accumulates once the provider's gate-on capture hook dispatches events, and registers
    // its interceptor lazily on the first such event.
    SPAN_ENRICHMENT_WRITER = SpanEnrichmentWriter.getInstance();
    SPAN_ENRICHMENT_WRITER.init();

    LOGGER.debug("Feature Flagging system started");
  }

  private static void initializeExposureWriter(
      final SharedCommunicationObjects sco,
      final Config config,
      final ExposureWriterFactory exposureWriterFactory) {
    final ExposureWriter exposureWriter = exposureWriterFactory.create(sco, config);
    try {
      exposureWriter.init();
      EXPOSURE_WRITER = exposureWriter;
    } catch (final RuntimeException | Error e) {
      exposureWriter.close();
      throw e;
    }
  }

  private static void initializeConfigurationSource(
      final ConfigurationSourceService configService, final ExposureWriter exposureWriter) {
    try {
      configService.init();
      CONFIG_SERVICE = configService;
    } catch (final RuntimeException | Error e) {
      EXPOSURE_WRITER = null;
      try {
        exposureWriter.close();
      } finally {
        configService.close();
      }
      throw e;
    }
  }

  static void initialize(
      final ConfigurationSourceService configService, final ExposureWriter exposureWriter) {
    try {
      if (configService != null) {
        configService.init();
      }
      exposureWriter.init();
      CONFIG_SERVICE = configService;
      EXPOSURE_WRITER = exposureWriter;
    } catch (final RuntimeException | Error e) {
      try {
        exposureWriter.close();
      } finally {
        if (configService != null) {
          configService.close();
        }
      }
      throw e;
    }
  }

  static ConfigurationSourceService createConfigurationSourceService(
      final SharedCommunicationObjects sco, final Config config) {
    final String configurationSource = config.getFeatureFlaggingConfigurationSource();
    if (CONFIGURATION_SOURCE_REMOTE_CONFIG.equals(configurationSource)) {
      if (!config.isRemoteConfigEnabled()) {
        throw new IllegalStateException("Feature Flagging system started without RC");
      }
      return new RemoteConfigServiceImpl(sco, config);
    }
    if (CONFIGURATION_SOURCE_AGENTLESS.equals(configurationSource)) {
      return new AgentlessConfigurationSource(config);
    }
    return null;
  }

  @SuppressFBWarnings(
      value = "USO_UNSAFE_STATIC_METHOD_SYNCHRONIZATION",
      justification =
          "Agent-internal class; Class object does not escape to app code and lock only guards the subsystem lifecycle.")
  public static synchronized void stop() {
    FeatureFlaggingGateway.setFlagEvaluationEnqueueEnabled(false);
    FeatureFlaggingGateway.setFlagEvalWriter(null);
    final FeatureFlaggingGateway.ActivationListener activationListener = ACTIVATION_LISTENER;
    final FlagEvaluationWriter flagEvalWriter = FLAG_EVAL_WRITER;
    final SpanEnrichmentWriter spanEnrichmentWriter = SPAN_ENRICHMENT_WRITER;
    final ExposureWriter exposureWriter = EXPOSURE_WRITER;
    final ConfigurationSourceService configService = CONFIG_SERVICE;
    STARTED = false;
    ACTIVATION_LISTENER = null;
    FLAG_EVAL_WRITER = null;
    SPAN_ENRICHMENT_WRITER = null;
    EXPOSURE_WRITER = null;
    CONFIG_SERVICE = null;
    if (activationListener != null) {
      FeatureFlaggingGateway.removeActivationListener(activationListener);
    }
    closeQuietly(flagEvalWriter);
    closeQuietly(spanEnrichmentWriter);
    closeQuietly(exposureWriter);
    closeQuietly(configService);
    LOGGER.debug("Feature Flagging system stopped");
  }

  static boolean isAwaitingApplicationActivation() {
    return ACTIVATION_LISTENER != null;
  }

  static boolean isExposureWriterStarted() {
    return EXPOSURE_WRITER != null;
  }

  static boolean isExposureWriterRunning() {
    final ExposureWriter exposureWriter = EXPOSURE_WRITER;
    return exposureWriter instanceof ExposureWriterImpl
        && ((ExposureWriterImpl) exposureWriter).isSerializerThreadAlive();
  }

  static boolean isConfigurationSourceStarted() {
    return CONFIG_SERVICE != null;
  }

  private static void closeQuietly(final AutoCloseable resource) {
    if (resource != null) {
      try {
        resource.close();
      } catch (Exception ignored) {
      }
    }
  }
}

package com.datadog.featureflag;

import static datadog.trace.api.featureflag.config.FeatureFlaggingConfig.CONFIGURATION_SOURCE_AGENTLESS;
import static datadog.trace.api.featureflag.config.FeatureFlaggingConfig.CONFIGURATION_SOURCE_REMOTE_CONFIG;
import static datadog.trace.util.AgentThreadFactory.AgentThread.FEATURE_FLAG_SHUTDOWN;
import static datadog.trace.util.AgentThreadFactory.newAgentThread;

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
  interface SystemInitializer {
    void initialize(SharedCommunicationObjects sco, Config config);
  }

  private static final Logger LOGGER = LoggerFactory.getLogger(FeatureFlaggingSystem.class);

  private static volatile ConfigurationSourceService CONFIG_SERVICE;
  private static volatile ExposureWriter EXPOSURE_WRITER;
  private static volatile FlagEvaluationWriter FLAG_EVAL_WRITER;
  private static volatile SpanEnrichmentWriter SPAN_ENRICHMENT_WRITER;
  private static volatile FeatureFlaggingGateway.ActivationListener ACTIVATION_LISTENER;
  private static volatile boolean STARTED;
  private static Thread SHUTDOWN_HOOK;

  private FeatureFlaggingSystem() {}

  public static void start(final SharedCommunicationObjects sco) {
    start(sco, FeatureFlaggingSystem::initializeSystem);
  }

  static synchronized void start(
      final SharedCommunicationObjects sco, final SystemInitializer systemInitializer) {
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
          () -> activateAgentless(sco, config, systemInitializer);
      ACTIVATION_LISTENER = activationListener;
      FeatureFlaggingGateway.addActivationListener(activationListener);
      LOGGER.debug("Feature Flagging system awaiting application provider activation");
      return;
    }

    initializeOrRollBack(sco, config, systemInitializer);
  }

  private static synchronized void activateAgentless(
      final SharedCommunicationObjects sco,
      final Config config,
      final SystemInitializer systemInitializer) {
    final FeatureFlaggingGateway.ActivationListener activationListener = ACTIVATION_LISTENER;
    if (!STARTED || activationListener == null) {
      return;
    }
    ACTIVATION_LISTENER = null;
    FeatureFlaggingGateway.removeActivationListener(activationListener);
    initializeOrRollBack(sco, config, systemInitializer);
  }

  // Any failure leaves the subsystem fully stopped: stop() releases whatever initializeSystem
  // managed to publish before it threw, so a later start() begins from a clean state.
  private static void initializeOrRollBack(
      final SharedCommunicationObjects sco,
      final Config config,
      final SystemInitializer systemInitializer) {
    try {
      systemInitializer.initialize(sco, config);
      if (!config.isTraceEnabled() && (EXPOSURE_WRITER != null || FLAG_EVAL_WRITER != null)) {
        // Tracing already owns the pre-application-hook shutdown path. Without it, only an
        // activated Feature Flagging subsystem needs a hook; do not stop other products.
        final Thread hook = newAgentThread(FEATURE_FLAG_SHUTDOWN, FeatureFlaggingSystem::stop);
        Runtime.getRuntime().addShutdownHook(hook);
        SHUTDOWN_HOOK = hook;
      }
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
    final FeatureFlagRouteSelector routeSelector = new FeatureFlagRouteSelector();
    final ExposureWriter exposureWriter = new ExposureWriterImpl(sco, config, routeSelector);
    initialize(configService, exposureWriter);

    final boolean evalCountsEnabled =
        config
            .configProvider()
            .getBoolean(FeatureFlaggingConfig.FLAGGING_EVALUATION_COUNTS_ENABLED, true);
    FeatureFlaggingGateway.setFlagEvaluationEnqueueEnabled(evalCountsEnabled);
    if (evalCountsEnabled) {
      final FlagEvaluationWriterImpl evalWriter =
          new FlagEvaluationWriterImpl(sco, config, routeSelector);
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
    final long deadline = FeatureFlagEventWriter.shutdownDeadline();
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
    final Thread shutdownHook = SHUTDOWN_HOOK;
    SHUTDOWN_HOOK = null;
    if (shutdownHook != null && shutdownHook != Thread.currentThread()) {
      try {
        Runtime.getRuntime().removeShutdownHook(shutdownHook);
      } catch (IllegalStateException ignored) {
        // JVM shutdown has already started. The idempotent hook will observe the stopped state.
      }
    }
    if (activationListener != null) {
      FeatureFlaggingGateway.removeActivationListener(activationListener);
    }
    requestWriterShutdown(flagEvalWriter);
    requestWriterShutdown(exposureWriter);
    closeQuietly(spanEnrichmentWriter);
    closeQuietly(configService);
    awaitWriterTermination(flagEvalWriter, deadline);
    awaitWriterTermination(exposureWriter, deadline);
    LOGGER.debug("Feature Flagging system stopped");
  }

  static boolean isAwaitingApplicationActivation() {
    return ACTIVATION_LISTENER != null;
  }

  static boolean isShutdownHookRegistered() {
    return SHUTDOWN_HOOK != null;
  }

  private static void requestWriterShutdown(final AutoCloseable writer) {
    if (writer instanceof FeatureFlagEventWriter) {
      ((FeatureFlagEventWriter) writer).requestShutdown();
    } else {
      closeQuietly(writer);
    }
  }

  private static void awaitWriterTermination(final AutoCloseable writer, final long deadline) {
    if (writer instanceof FeatureFlagEventWriter) {
      ((FeatureFlagEventWriter) writer).awaitTermination(deadline);
    }
  }

  static boolean isExposureWriterStarted() {
    return EXPOSURE_WRITER != null;
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

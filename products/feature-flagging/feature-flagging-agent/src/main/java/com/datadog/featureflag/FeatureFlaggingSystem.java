package com.datadog.featureflag;

import static datadog.trace.api.featureflag.config.FeatureFlaggingConfig.CONFIGURATION_SOURCE_AGENTLESS;
import static datadog.trace.api.featureflag.config.FeatureFlaggingConfig.CONFIGURATION_SOURCE_REMOTE_CONFIG;

import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.trace.api.Config;
import datadog.trace.api.featureflag.FeatureFlaggingGateway;
import datadog.trace.api.featureflag.FeatureFlaggingGateway.RuntimeMode;
import datadog.trace.api.featureflag.config.FeatureFlaggingConfig;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FeatureFlaggingSystem {

  @FunctionalInterface
  interface SystemInitializer {
    void initialize(SharedCommunicationObjects sco, Config config);
  }

  private static final Logger LOGGER = LoggerFactory.getLogger(FeatureFlaggingSystem.class);

  private static volatile ProviderRuntime RUNTIME;
  private static volatile SpanEnrichmentWriter SPAN_ENRICHMENT_WRITER;
  private static volatile FeatureFlaggingGateway.ActivationListener ACTIVATION_LISTENER;
  private static volatile boolean STARTED;

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
    if (!FeatureFlaggingGateway.claimRuntime(RuntimeMode.AGENT)) {
      LOGGER.debug(
          "Feature Flagging agent runtime not started because {} already owns the subsystem",
          FeatureFlaggingGateway.activeRuntime());
      return;
    }
    try {
      systemInitializer.initialize(sco, config);
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
    final ExposureWriter exposureWriter = new ExposureWriterImpl(sco, config);

    final boolean evalCountsEnabled =
        config
            .configProvider()
            .getBoolean(FeatureFlaggingConfig.FLAGGING_EVALUATION_COUNTS_ENABLED, true);
    RUNTIME =
        ProviderRuntime.start(
            configService,
            exposureWriter,
            () -> new FlagEvaluationWriterImpl(sco, config),
            evalCountsEnabled);

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
    RUNTIME = ProviderRuntime.start(configService, exposureWriter, null, false);
  }

  static ConfigurationSourceService createConfigurationSourceService(
      final SharedCommunicationObjects sco, final Config config) {
    final String configurationSource = config.getFeatureFlaggingConfigurationSource();
    if (CONFIGURATION_SOURCE_REMOTE_CONFIG.equals(configurationSource)) {
      if (!config.isRemoteConfigEnabled()) {
        throw new IllegalStateException("Feature Flagging system started without RC");
      }
      return new RemoteConfigServiceImpl(sco.configurationPoller(config));
    }
    if (CONFIGURATION_SOURCE_AGENTLESS.equals(configurationSource)) {
      return new AgentlessConfigurationSource(config, AgentRuntimeServices.INSTANCE);
    }
    return null;
  }

  @SuppressFBWarnings(
      value = "USO_UNSAFE_STATIC_METHOD_SYNCHRONIZATION",
      justification =
          "Agent-internal class; Class object does not escape to app code and lock only guards the subsystem lifecycle.")
  public static synchronized void stop() {
    if (FeatureFlaggingGateway.activeRuntime() != RuntimeMode.STANDALONE) {
      FeatureFlaggingGateway.setFlagEvaluationEnqueueEnabled(false);
      FeatureFlaggingGateway.setFlagEvalWriter(null);
    }
    final FeatureFlaggingGateway.ActivationListener activationListener = ACTIVATION_LISTENER;
    final ProviderRuntime runtime = RUNTIME;
    final SpanEnrichmentWriter spanEnrichmentWriter = SPAN_ENRICHMENT_WRITER;
    STARTED = false;
    ACTIVATION_LISTENER = null;
    RUNTIME = null;
    SPAN_ENRICHMENT_WRITER = null;
    if (activationListener != null) {
      FeatureFlaggingGateway.removeActivationListener(activationListener);
    }
    closeQuietly(spanEnrichmentWriter);
    closeQuietly(runtime);
    FeatureFlaggingGateway.releaseRuntime(RuntimeMode.AGENT);
    LOGGER.debug("Feature Flagging system stopped");
  }

  static boolean isAwaitingApplicationActivation() {
    return ACTIVATION_LISTENER != null;
  }

  static boolean isExposureWriterStarted() {
    return RUNTIME != null;
  }

  static boolean isConfigurationSourceStarted() {
    final ProviderRuntime runtime = RUNTIME;
    return runtime != null && runtime.hasConfigurationSource();
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

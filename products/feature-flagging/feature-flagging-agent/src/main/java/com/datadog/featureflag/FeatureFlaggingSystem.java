package com.datadog.featureflag;

import static datadog.trace.api.featureflag.config.FeatureFlaggingConfig.CONFIGURATION_SOURCE_AGENTLESS;
import static datadog.trace.api.featureflag.config.FeatureFlaggingConfig.CONFIGURATION_SOURCE_REMOTE_CONFIG;

import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.trace.api.Config;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FeatureFlaggingSystem {

  private static final Logger LOGGER = LoggerFactory.getLogger(FeatureFlaggingSystem.class);

  private static volatile ConfigurationSourceService CONFIG_SERVICE;
  private static volatile ExposureWriter EXPOSURE_WRITER;
  private static volatile SpanEnrichmentWriter SPAN_ENRICHMENT_WRITER;
  private static volatile boolean STARTED;

  private FeatureFlaggingSystem() {}

  @SuppressFBWarnings(
      value = "USO_UNSAFE_STATIC_METHOD_SYNCHRONIZATION",
      justification =
          "Agent-internal class; Class object does not escape to app code and lock only guards the subsystem lifecycle.")
  public static synchronized void start(final SharedCommunicationObjects sco) {
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

    try {
      initializeSystem(sco, config);
    } catch (final RuntimeException | Error e) {
      STARTED = false;
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
    initialize(configService, exposureWriter);

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
    final SpanEnrichmentWriter spanEnrichmentWriter = SPAN_ENRICHMENT_WRITER;
    final ExposureWriter exposureWriter = EXPOSURE_WRITER;
    final ConfigurationSourceService configService = CONFIG_SERVICE;
    STARTED = false;
    SPAN_ENRICHMENT_WRITER = null;
    EXPOSURE_WRITER = null;
    CONFIG_SERVICE = null;
    try {
      if (spanEnrichmentWriter != null) {
        spanEnrichmentWriter.close();
      }
    } finally {
      try {
        if (exposureWriter != null) {
          exposureWriter.close();
        }
      } finally {
        if (configService != null) {
          configService.close();
        }
      }
    }
    LOGGER.debug("Feature Flagging system stopped");
  }

  static boolean isAwaitingApplicationActivation() {
    return false;
  }
}

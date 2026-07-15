package com.datadog.featureflag;

import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.trace.api.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FeatureFlaggingSystem {

  private static final Logger LOGGER = LoggerFactory.getLogger(FeatureFlaggingSystem.class);

  private static volatile ConfigurationSourceService CONFIG_SERVICE;
  private static volatile ExposureWriter EXPOSURE_WRITER;
  private static volatile SpanEnrichmentWriter SPAN_ENRICHMENT_WRITER;

  private FeatureFlaggingSystem() {}

  public static synchronized void start(final SharedCommunicationObjects sco) {
    if (CONFIG_SERVICE != null || EXPOSURE_WRITER != null) {
      LOGGER.debug("Feature Flagging system already started");
      return;
    }
    LOGGER.debug("Feature Flagging system starting");
    final Config config = Config.get();
    final ConfigurationSourceService configService = createConfigurationSourceService(sco, config);
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
    final ConfigurationSource configurationSource =
        ConfigurationSource.from(config.getFeatureFlaggingConfigurationSource());

    if (configurationSource == ConfigurationSource.REMOTE_CONFIG) {
      if (!config.isRemoteConfigEnabled()) {
        throw new IllegalStateException("Feature Flagging system started without RC");
      }
      return new RemoteConfigServiceImpl(sco, config);
    }
    if (configurationSource == ConfigurationSource.AGENTLESS) {
      return new AgentlessConfigurationSource(config);
    }
    LOGGER.debug(
        "Feature Flagging offline configuration source selected; no config service started");
    return null;
  }

  public static synchronized void stop() {
    final SpanEnrichmentWriter spanEnrichmentWriter = SPAN_ENRICHMENT_WRITER;
    final ExposureWriter exposureWriter = EXPOSURE_WRITER;
    final ConfigurationSourceService configService = CONFIG_SERVICE;
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

  private enum ConfigurationSource {
    AGENTLESS("agentless"),
    REMOTE_CONFIG("remote_config"),
    OFFLINE("offline");

    private final String value;

    ConfigurationSource(final String value) {
      this.value = value;
    }

    private static ConfigurationSource from(final String value) {
      for (final ConfigurationSource source : values()) {
        if (source.value.equals(value)) {
          return source;
        }
      }
      throw new IllegalArgumentException(
          "Unsupported Feature Flagging configuration source: " + value);
    }
  }
}

package datadog.trace.common.writer;

import static datadog.trace.api.config.TracerConfig.PRIORITIZATION_TYPE;
import static datadog.trace.bootstrap.instrumentation.api.WriterConstants.DD_AGENT_WRITER_TYPE;
import static datadog.trace.bootstrap.instrumentation.api.WriterConstants.DD_INTAKE_WRITER_TYPE;
import static datadog.trace.bootstrap.instrumentation.api.WriterConstants.LOGGING_WRITER_TYPE;
import static datadog.trace.bootstrap.instrumentation.api.WriterConstants.MULTI_WRITER_TYPE;
import static datadog.trace.bootstrap.instrumentation.api.WriterConstants.PRINTING_WRITER_TYPE;
import static datadog.trace.bootstrap.instrumentation.api.WriterConstants.TRACE_STRUCTURE_WRITER_TYPE;
import static datadog.trace.common.writer.ddagent.Prioritization.ENSURE_TRACE;
import static datadog.trace.common.writer.ddagent.Prioritization.FAST_LANE;

import datadog.common.container.ServerlessInfo;
import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.trace.api.Config;
import datadog.trace.api.StatsDClient;
import datadog.trace.api.intake.TrackType;
import datadog.trace.common.sampling.Sampler;
import datadog.trace.common.sampling.SingleSpanSampler;
import datadog.trace.common.writer.ddagent.DDAgentApi;
import datadog.trace.common.writer.ddagent.Prioritization;
import datadog.trace.common.writer.ddintake.DDEvpProxyApi;
import datadog.trace.common.writer.ddintake.DDIntakeApi;
import datadog.trace.common.writer.ddintake.DDIntakeTrackTypeResolver;
import datadog.trace.core.monitor.TracerHealthMetrics;
import datadog.trace.util.Strings;
import okhttp3.HttpUrl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WriterFactory {

  private static final Logger log = LoggerFactory.getLogger(WriterFactory.class);

  public static Writer createWriter(
      final Config config,
      final SharedCommunicationObjects commObjects,
      final Sampler sampler,
      final SingleSpanSampler singleSpanSampler,
      final StatsDClient statsDClient) {
    return createWriter(
        config, commObjects, sampler, singleSpanSampler, statsDClient, config.getWriterType());
  }

  public static Writer createWriter(
      final Config config,
      final SharedCommunicationObjects commObjects,
      final Sampler sampler,
      final SingleSpanSampler singleSpanSampler,
      final StatsDClient statsDClient,
      String configuredType) {

    if (LOGGING_WRITER_TYPE.equals(configuredType)) {
      return new LoggingWriter();
    } else if (PRINTING_WRITER_TYPE.equals(configuredType)) {
      return new PrintingWriter(System.out, true);
    } else if (configuredType.startsWith(TRACE_STRUCTURE_WRITER_TYPE)) {
      return new TraceStructureWriter(
          Strings.replace(configuredType, TRACE_STRUCTURE_WRITER_TYPE, ""));
    } else if (configuredType.startsWith(MULTI_WRITER_TYPE)) {
      return new MultiWriter(
          config, commObjects, sampler, singleSpanSampler, statsDClient, configuredType);
    }

    if (!DD_AGENT_WRITER_TYPE.equals(configuredType)
        && !DD_INTAKE_WRITER_TYPE.equals(configuredType)) {
      log.warn(
          "Writer type not configured correctly: Type {} not recognized. Ignoring", configuredType);
      configuredType = datadog.trace.api.ConfigDefaults.DEFAULT_AGENT_WRITER_TYPE;
    }

    Prioritization prioritization =
        config.getEnumValue(PRIORITIZATION_TYPE, Prioritization.class, FAST_LANE);
    if (ENSURE_TRACE == prioritization) {
      log.info(
          "Using 'EnsureTrace' prioritization type. (Do not use this type if your application is running in production mode)");
    }

    int flushIntervalMilliseconds = Math.round(config.getTraceFlushIntervalSeconds() * 1000);
    DDAgentFeaturesDiscovery featuresDiscovery = commObjects.featuresDiscovery(config);

    // The AgentWriter doesn't support the CI Visibility protocol. If CI Visibility is
    // enabled, check if we can use the IntakeWriter instead.
    if (DD_AGENT_WRITER_TYPE.equals(configuredType) && config.isCiVisibilityEnabled()) {
      if (featuresDiscovery.supportsEvpProxy() || config.isCiVisibilityAgentlessEnabled()) {
        configuredType = DD_INTAKE_WRITER_TYPE;
      } else {
        log.info(
            "CI Visibility functionality is limited. Please upgrade to Agent v6.40+ or v7.40+ or enable Agentless mode.");
      }
    }

    RemoteWriter remoteWriter;
    if (DD_INTAKE_WRITER_TYPE.equals(configuredType)) {
      final TrackType trackType = DDIntakeTrackTypeResolver.resolve(config);
      final RemoteApi remoteApi =
          createDDIntakeRemoteApi(config, commObjects, featuresDiscovery, trackType);

      DDIntakeWriter.DDIntakeWriterBuilder builder =
          DDIntakeWriter.builder()
              .addTrack(trackType, remoteApi)
              .prioritization(prioritization)
              .healthMetrics(new TracerHealthMetrics(statsDClient))
              .monitoring(commObjects.monitoring)
              .singleSpanSampler(singleSpanSampler)
              .flushIntervalMilliseconds(flushIntervalMilliseconds);

      if (config.isCiVisibilityCodeCoverageEnabled()) {
        final RemoteApi coverageApi =
            createDDIntakeRemoteApi(config, commObjects, featuresDiscovery, TrackType.CITESTCOV);
        builder.addTrack(TrackType.CITESTCOV, coverageApi);
      }

      remoteWriter = builder.build();

    } else { // configuredType == DDAgentWriter
      boolean alwaysFlush = false;
      if (config.isAgentConfiguredUsingDefault()
          && ServerlessInfo.get().isRunningInServerlessEnvironment()) {
        if (!ServerlessInfo.get().hasExtension()) {
          log.info(
              "Detected serverless environment. Serverless extension has not been detected, using PrintingWriter");
          return new PrintingWriter(System.out, true);
        } else {
          log.info(
              "Detected serverless environment. Serverless extension has been detected, using DDAgentWriter");
          alwaysFlush = true;
        }
      }

      DDAgentApi ddAgentApi =
          new DDAgentApi(
              commObjects.okHttpClient,
              commObjects.agentUrl,
              featuresDiscovery,
              commObjects.monitoring,
              config.isTracerMetricsEnabled());

      if (sampler instanceof RemoteResponseListener) {
        ddAgentApi.addResponseListener((RemoteResponseListener) sampler);
      }

      remoteWriter =
          DDAgentWriter.builder()
              .agentApi(ddAgentApi)
              .featureDiscovery(featuresDiscovery)
              .prioritization(prioritization)
              .healthMetrics(new TracerHealthMetrics(statsDClient))
              .monitoring(commObjects.monitoring)
              .alwaysFlush(alwaysFlush)
              .spanSamplingRules(singleSpanSampler)
              .flushIntervalMilliseconds(flushIntervalMilliseconds)
              .build();
    }

    return remoteWriter;
  }

  private static RemoteApi createDDIntakeRemoteApi(
      Config config,
      SharedCommunicationObjects commObjects,
      DDAgentFeaturesDiscovery featuresDiscovery,
      TrackType trackType) {
    if (featuresDiscovery.supportsEvpProxy() && !config.isCiVisibilityAgentlessEnabled()) {
      return DDEvpProxyApi.builder()
          .agentUrl(commObjects.agentUrl)
          .evpProxyEndpoint(featuresDiscovery.getEvpProxyEndpoint())
          .trackType(trackType)
          .build();

    } else {
      HttpUrl hostUrl = null;
      if (config.getCiVisibilityAgentlessUrl() != null) {
        hostUrl = HttpUrl.get(config.getCiVisibilityAgentlessUrl());
        log.info("Using host URL '{}' to report CI Visibility traces in Agentless mode.", hostUrl);
      }
      return DDIntakeApi.builder()
          .hostUrl(hostUrl)
          .apiKey(config.getApiKey())
          .trackType(trackType)
          .build();
    }
  }

  private WriterFactory() {}
}

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
import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.trace.api.Config;
import datadog.trace.api.StatsDClient;
import datadog.trace.api.intake.TrackType;
import datadog.trace.common.sampling.Sampler;
import datadog.trace.common.writer.ddagent.DDAgentApi;
import datadog.trace.common.writer.ddagent.Prioritization;
import datadog.trace.common.writer.ddintake.DDIntakeApi;
import datadog.trace.common.writer.ddintake.DDIntakeTrackTypeResolver;
import datadog.trace.core.monitor.HealthMetrics;
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
      final StatsDClient statsDClient) {
    return createWriter(config, commObjects, sampler, statsDClient, config.getWriterType());
  }

  public static Writer createWriter(
      final Config config,
      final SharedCommunicationObjects commObjects,
      final Sampler sampler,
      final StatsDClient statsDClient,
      final String configuredType) {

    if (LOGGING_WRITER_TYPE.equals(configuredType)) {
      return new LoggingWriter();
    } else if (PRINTING_WRITER_TYPE.equals(configuredType)) {
      return new PrintingWriter(System.out, true);
    } else if (configuredType.startsWith(TRACE_STRUCTURE_WRITER_TYPE)) {
      return new TraceStructureWriter(
          Strings.replace(configuredType, TRACE_STRUCTURE_WRITER_TYPE, ""));
    } else if (configuredType.startsWith(MULTI_WRITER_TYPE)) {
      return new MultiWriter(config, commObjects, sampler, statsDClient, configuredType);
    }

    Prioritization prioritization =
        config.getEnumValue(PRIORITIZATION_TYPE, Prioritization.class, FAST_LANE);
    if (ENSURE_TRACE == prioritization) {
      log.info(
          "Using 'EnsureTrace' prioritization type. (Do not use this type if your application is running in production mode)");
    }

    RemoteWriter remoteWriter;
    if (DD_INTAKE_WRITER_TYPE.equals(configuredType)) {
      final TrackType trackType = DDIntakeTrackTypeResolver.resolve(config);
      final String apiKey = config.getApiKey();
      if (apiKey == null || apiKey.isEmpty()) {
        log.warn("Api Key has not been detected, using PrinterWriter.");
        return new PrintingWriter(System.out, true);
      }

      HttpUrl hostUrl = null;
      if (config.getCiVisibilityAgentlessUrl() != null) {
        hostUrl = HttpUrl.get(config.getCiVisibilityAgentlessUrl());
        log.info(
            "Using host URL '" + hostUrl + "' to report CI Visibility traces in Agentless mode.");
      }

      final DDIntakeApi ddIntakeApi =
          DDIntakeApi.builder()
              .hostUrl(hostUrl)
              .apiKey(config.getApiKey())
              .trackType(trackType)
              .build();

      remoteWriter =
          DDIntakeWriter.builder()
              .intakeApi(ddIntakeApi)
              .trackType(trackType)
              .prioritization(prioritization)
              .healthMetrics(new HealthMetrics(statsDClient))
              .monitoring(commObjects.monitoring)
              .build();
    } else {
      if (!DD_AGENT_WRITER_TYPE.equals(configuredType)) {
        log.warn(
            "Writer type not configured correctly: Type {} not recognized. Ignoring",
            configuredType);
      }

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
              commObjects.featuresDiscovery(config),
              commObjects.monitoring,
              config.isTracerMetricsEnabled());

      remoteWriter =
          DDAgentWriter.builder()
              .agentApi(ddAgentApi)
              .featureDiscovery(commObjects.featuresDiscovery(config))
              .prioritization(prioritization)
              .healthMetrics(new HealthMetrics(statsDClient))
              .monitoring(commObjects.monitoring)
              .alwaysFlush(alwaysFlush)
              .build();
    }

    if (sampler instanceof RemoteResponseListener) {
      remoteWriter.addResponseListener((RemoteResponseListener) sampler);
    }

    return remoteWriter;
  }

  private WriterFactory() {}
}

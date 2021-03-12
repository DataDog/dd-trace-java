package datadog.trace.common.writer;

import static datadog.trace.api.config.TracerConfig.PRIORITIZATION_TYPE;
import static datadog.trace.bootstrap.instrumentation.api.WriterConstants.*;
import static datadog.trace.common.writer.ddagent.Prioritization.ENSURE_TRACE;
import static datadog.trace.common.writer.ddagent.Prioritization.FAST_LANE;
import static datadog.trace.core.http.OkHttpUtils.buildHttpClient;

import com.timgroup.statsd.StatsDClient;
import datadog.common.container.ServerlessInfo;
import datadog.trace.api.Config;
import datadog.trace.api.ConfigDefaults;
import datadog.trace.api.config.TracerConfig;
import datadog.trace.common.sampling.Sampler;
import datadog.trace.common.writer.ddagent.DDAgentApi;
import datadog.trace.common.writer.ddagent.DDAgentFeaturesDiscovery;
import datadog.trace.common.writer.ddagent.DDAgentResponseListener;
import datadog.trace.common.writer.ddagent.Prioritization;
import datadog.trace.core.monitor.HealthMetrics;
import datadog.trace.core.monitor.Monitoring;
import datadog.trace.util.Strings;
import java.util.concurrent.TimeUnit;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WriterFactory {

  private static final Logger log = LoggerFactory.getLogger(WriterFactory.class);

  public static Writer createWriter(
      final Config config,
      final Sampler sampler,
      final StatsDClient statsDClient,
      final Monitoring monitoring) {
    return createWriter(config, sampler, statsDClient, monitoring, config.getWriterType());
  }

  public static Writer createWriter(
      final Config config,
      final Sampler sampler,
      final StatsDClient statsDClient,
      final Monitoring monitoring,
      final String configuredType) {

    if (LOGGING_WRITER_TYPE.equals(configuredType)) {
      return new LoggingWriter();
    } else if (PRINTING_WRITER_TYPE.equals(configuredType)) {
      return new PrintingWriter(System.out, true);
    } else if (configuredType.startsWith(TRACE_STRUCTURE_WRITER_TYPE)) {
      return new TraceStructureWriter(
          Strings.replace(configuredType, TRACE_STRUCTURE_WRITER_TYPE, ""));
    } else if (configuredType.startsWith(MULTI_WRITER_TYPE)) {
      return new MultiWriter(config, sampler, statsDClient, monitoring, configuredType);
    }

    if (!DD_AGENT_WRITER_TYPE.equals(configuredType)) {
      log.warn(
          "Writer type not configured correctly: Type {} not recognized. Ignoring", configuredType);
    }

    if (config.isAgentConfiguredUsingDefault()
        && ServerlessInfo.get().isRunningInServerlessEnvironment()) {
      log.info("Detected serverless environment.  Using PrintingWriter");
      return new PrintingWriter(System.out, true);
    }

    String unixDomainSocket = config.getAgentUnixDomainSocket();
    if (unixDomainSocket != ConfigDefaults.DEFAULT_AGENT_UNIX_DOMAIN_SOCKET && isWindows()) {
      log.warn(
          "{} setting not supported on {}.  Reverting to the default.",
          TracerConfig.AGENT_UNIX_DOMAIN_SOCKET,
          System.getProperty("os.name"));
      unixDomainSocket = ConfigDefaults.DEFAULT_AGENT_UNIX_DOMAIN_SOCKET;
    }

    HttpUrl agentUrl = HttpUrl.get(config.getAgentUrl());
    OkHttpClient client =
        buildHttpClient(
            agentUrl, unixDomainSocket, TimeUnit.SECONDS.toMillis(config.getAgentTimeout()));

    DDAgentFeaturesDiscovery featuresDiscovery =
        new DDAgentFeaturesDiscovery(
            client,
            monitoring,
            agentUrl,
            Config.get().isTraceAgentV05Enabled(),
            Config.get().isTracerMetricsEnabled());

    DDAgentApi ddAgentApi =
        new DDAgentApi(
            client, agentUrl, featuresDiscovery, monitoring, Config.get().isTracerMetricsEnabled());

    Prioritization prioritization =
        config.getEnumValue(PRIORITIZATION_TYPE, Prioritization.class, FAST_LANE);
    if (ENSURE_TRACE == prioritization) {
      log.info(
          "Using 'EnsureTrace' prioritization type. (Do not use this type if your application is running in production mode)");
    }

    final DDAgentWriter ddAgentWriter =
        DDAgentWriter.builder()
            .agentApi(ddAgentApi)
            .featureDiscovery(featuresDiscovery)
            .prioritization(prioritization)
            .healthMetrics(new HealthMetrics(statsDClient))
            .monitoring(monitoring)
            .build();

    if (sampler instanceof DDAgentResponseListener) {
      ddAgentWriter.addResponseListener((DDAgentResponseListener) sampler);
    }

    return ddAgentWriter;
  }

  private static boolean isWindows() {
    // https://mkyong.com/java/how-to-detect-os-in-java-systemgetpropertyosname/
    final String os = System.getProperty("os.name").toLowerCase();
    return os.contains("win");
  }

  private WriterFactory() {}
}

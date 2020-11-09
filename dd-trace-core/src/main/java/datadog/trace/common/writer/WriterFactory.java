package datadog.trace.common.writer;

import static datadog.trace.bootstrap.instrumentation.api.PrioritizationConstants.ENSURE_TRACE_TYPE;
import static datadog.trace.bootstrap.instrumentation.api.WriterConstants.*;

import com.timgroup.statsd.StatsDClient;
import datadog.common.container.ServerlessInfo;
import datadog.trace.api.Config;
import datadog.trace.api.ConfigDefaults;
import datadog.trace.api.config.TracerConfig;
import datadog.trace.common.sampling.Sampler;
import datadog.trace.common.writer.ddagent.DDAgentApi;
import datadog.trace.common.writer.ddagent.DDAgentResponseListener;
import datadog.trace.common.writer.ddagent.Prioritization;
import datadog.trace.core.monitor.HealthMetrics;
import datadog.trace.core.monitor.Monitoring;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class WriterFactory {

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
      return new TraceStructureWriter(configuredType.replace(TRACE_STRUCTURE_WRITER_TYPE, ""));
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

    final DDAgentApi ddAgentApi =
        new DDAgentApi(
            config.getAgentUrl(),
            unixDomainSocket,
            TimeUnit.SECONDS.toMillis(config.getAgentTimeout()),
            Config.get().isTraceAgentV05Enabled(),
            Config.get().isTracerMetricsEnabled(),
            monitoring);

    final String prioritizationType = config.getPrioritizationType();
    Prioritization prioritization = null;
    if (ENSURE_TRACE_TYPE.equals(prioritizationType)) {
      prioritization = Prioritization.ENSURE_TRACE;
      log.info(
          "Using 'EnsureTrace' prioritization type. (Do not use this type if your application is running in production mode)");
    }

    final DDAgentWriter ddAgentWriter =
        DDAgentWriter.builder()
            .agentApi(ddAgentApi)
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

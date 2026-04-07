package datadog.trace.core.otlp.metrics;

import static datadog.trace.util.AgentThreadFactory.AgentThread.OTLP_METRICS_EXPORTER;

import datadog.trace.api.Config;
import datadog.trace.core.otlp.common.OtlpGrpcSender;
import datadog.trace.core.otlp.common.OtlpHttpSender;
import datadog.trace.core.otlp.common.OtlpPayload;
import datadog.trace.core.otlp.common.OtlpSender;
import datadog.trace.util.AgentTaskScheduler;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Periodic service to collect OpenTelemetry metrics and export them over OTLP. */
public final class OtlpMetricsService {
  private static final Logger LOGGER = LoggerFactory.getLogger(OtlpMetricsService.class);

  public static final OtlpMetricsService INSTANCE = new OtlpMetricsService(Config.get());

  private final AgentTaskScheduler scheduler;
  private final OtlpMetricsCollector collector;
  private final OtlpSender sender;

  private final int intervalMillis;

  private OtlpMetricsService(Config config) {
    this.scheduler = new AgentTaskScheduler(OTLP_METRICS_EXPORTER);

    switch (config.getOtlpMetricsProtocol()) {
      case GRPC:
        this.collector = OtlpMetricsProtoCollector.INSTANCE;
        this.sender =
            new OtlpGrpcSender(
                config.getOtlpMetricsEndpoint(),
                "/opentelemetry.proto.collector.metrics.v1.MetricsService/Export",
                config.getOtlpMetricsHeaders(),
                config.getOtlpMetricsTimeout(),
                config.getOtlpMetricsCompression());
        break;
      case HTTP_PROTOBUF:
        this.collector = OtlpMetricsProtoCollector.INSTANCE;
        this.sender =
            new OtlpHttpSender(
                config.getOtlpMetricsEndpoint(),
                "/v1/metrics",
                config.getOtlpMetricsHeaders(),
                config.getOtlpMetricsTimeout(),
                config.getOtlpMetricsCompression());
        break;
      default:
        LOGGER.debug("Unsupported OTLP metrics protocol: {}", config.getOtlpMetricsProtocol());
        this.collector = OtlpMetricsCollector.NOOP_COLLECTOR;
        this.sender = null;
    }

    this.intervalMillis = config.getMetricsOtelInterval();
  }

  public void start() {
    scheduler.scheduleAtFixedRate(
        this::export, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
  }

  public void flush() {
    scheduler.execute(this::export);
  }

  public void shutdown() {
    if (sender != null) {
      sender.shutdown();
    }
  }

  private void export() {
    OtlpPayload payload = collector.collectMetrics();
    if (payload != OtlpPayload.EMPTY) {
      sender.send(payload);
    }
  }
}

package datadog.trace.core.otlp.metrics;

import static datadog.trace.util.AgentThreadFactory.AgentThread.OTLP_METRICS_EXPORTER;

import datadog.trace.api.Config;
import datadog.trace.core.otlp.common.OtlpHttpSender;
import datadog.trace.core.otlp.common.OtlpSender;
import datadog.trace.util.AgentTaskScheduler;
import java.util.concurrent.TimeUnit;

/** Periodic service to collect OpenTelemetry metrics and export them over OTLP. */
public final class OtlpMetricsService {
  public static final OtlpMetricsService INSTANCE = new OtlpMetricsService(Config.get());

  private final AgentTaskScheduler scheduler;
  private final OtlpMetricsCollector collector;
  private final OtlpSender sender;

  private final int intervalMillis;

  private OtlpMetricsService(Config config) {
    this.scheduler = new AgentTaskScheduler(OTLP_METRICS_EXPORTER);
    this.collector = OtlpMetricsProtoCollector.INSTANCE;
    this.sender =
        new OtlpHttpSender(
            config.getOtlpMetricsEndpoint(),
            "/v1/metrics",
            config.getOtlpMetricsHeaders(),
            config.getOtlpMetricsTimeout(),
            config.getOtlpMetricsCompression());

    this.intervalMillis = config.getMetricsOtelInterval();
  }

  public void start() {
    scheduler.scheduleAtFixedRate(
        this::export, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
  }

  public void flush() {
    scheduler.execute(this::export);
  }

  private void export() {
    sender.send(collector.collectMetrics());
  }
}

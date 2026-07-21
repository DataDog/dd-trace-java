package datadog.trace.core.otlp.metrics;

import static datadog.trace.util.AgentThreadFactory.AgentThread.OTLP_METRICS_EXPORTER;

import datadog.trace.api.Config;
import datadog.trace.api.time.SystemTimeSource;
import datadog.trace.core.otlp.common.OtlpPayload;
import datadog.trace.core.otlp.common.OtlpSender;
import datadog.trace.util.AgentTaskScheduler;
import java.util.concurrent.ThreadLocalRandom;
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

  private AgentTaskScheduler.Scheduled<?> scheduledTask = null;

  private OtlpMetricsService(Config config) {
    this.scheduler = new AgentTaskScheduler(OTLP_METRICS_EXPORTER);

    this.sender = OtlpMetricsSenderFactory.create(config);
    if (this.sender == null) {
      LOGGER.debug("Unsupported OTLP metrics protocol: {}", config.getOtlpMetricsProtocol());
      this.collector = null;
    } else {
      this.collector = new OtlpMetricsProtoCollector(SystemTimeSource.INSTANCE);
    }

    this.intervalMillis = config.getMetricsOtelInterval();
  }

  public void start() {
    if (sender == null) {
      return;
    }

    // add random jitter of up to 5 seconds to initial delay; avoids a fleet
    // of apps starting at the same time from exporting OTLP metrics in sync
    long initialMillis =
        intervalMillis
            + Math.min(
                (long)
                    (500d
                        * Math.log(ThreadLocalRandom.current().nextDouble())
                        / Math.log(1 - 0.25)),
                5_000);

    scheduledTask =
        scheduler.scheduleAtFixedRate(
            this::export, initialMillis, intervalMillis, TimeUnit.MILLISECONDS);
  }

  public void flush() {
    if (sender != null) {
      scheduler.execute(this::export);
    }
  }

  public void shutdown() {
    if (scheduledTask != null) {
      scheduledTask.cancel();
    }
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

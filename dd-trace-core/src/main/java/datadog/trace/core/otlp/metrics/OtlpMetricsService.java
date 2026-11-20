package datadog.trace.core.otlp.metrics;

import static datadog.trace.core.monitor.ExporterId.Signal.METRIC_DATA_POINT;
import static datadog.trace.util.AgentThreadFactory.AgentThread.OTLP_METRICS_EXPORTER;

import datadog.trace.api.Config;
import datadog.trace.api.config.OtlpConfig;
import datadog.trace.api.internal.VisibleForTesting;
import datadog.trace.api.telemetry.OtlpTelemetry;
import datadog.trace.api.time.SystemTimeSource;
import datadog.trace.bootstrap.otel.metrics.data.OtelMetricRegistry;
import datadog.trace.common.writer.RemoteApi;
import datadog.trace.core.monitor.ComponentId;
import datadog.trace.core.monitor.ExportRecorder;
import datadog.trace.core.monitor.ExporterId;
import datadog.trace.core.monitor.HealthMetrics;
import datadog.trace.core.otlp.common.OtlpPayload;
import datadog.trace.core.otlp.common.OtlpSender;
import datadog.trace.util.AgentTaskScheduler;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Periodic service to collect OpenTelemetry metrics and export them over OTLP. */
public final class OtlpMetricsService {
  private static final Logger LOGGER = LoggerFactory.getLogger(OtlpMetricsService.class);

  public static final OtlpMetricsService INSTANCE = new OtlpMetricsService(Config.get());

  private static final int FINAL_EXPORT_TIMEOUT_SECONDS = 10;

  private final AgentTaskScheduler scheduler;
  private final OtlpMetricsCollector collector;
  private final OtlpSender sender;
  private final int intervalMillis;

  /** The {@code periodic_metric_reader} identity of this service's collection side. */
  private final ComponentId reader = ComponentId.next("periodic_metric_reader");

  /** The identity of this service's sender; null when there is no sender. */
  private final ExporterId exporter;

  private volatile HealthMetrics healthMetrics = HealthMetrics.NO_OP;
  private volatile ExportRecorder exportRecorder = ExportRecorder.NO_OP;

  private final Object lifecycleLock = new Object();

  private AgentTaskScheduler.Scheduled<?> scheduledTask;
  private CountDownLatch shutdownLatch;

  OtlpMetricsService(Config config) {
    this.scheduler = new AgentTaskScheduler(OTLP_METRICS_EXPORTER);
    this.sender = OtlpMetricsSenderFactory.create(config);
    this.exporter = exporterId(config.getOtlpMetricsProtocol(), sender);
    if (this.sender == null) {
      LOGGER.debug("Unsupported OTLP metrics protocol: {}", config.getOtlpMetricsProtocol());
      this.collector = null;
    } else {
      this.collector =
          config.getOtlpMetricsProtocol() == OtlpConfig.Protocol.HTTP_JSON
              ? new OtlpMetricsJsonCollector(SystemTimeSource.INSTANCE)
              : new OtlpMetricsProtoCollector(SystemTimeSource.INSTANCE);
    }

    this.intervalMillis = config.getMetricsOtelInterval();
  }

  OtlpMetricsService(
      AgentTaskScheduler scheduler,
      OtlpMetricsCollector collector,
      OtlpSender sender,
      OtlpConfig.Protocol protocol,
      int intervalMillis) {
    this.scheduler = scheduler;
    this.collector = collector;
    this.sender = sender;
    this.exporter = exporterId(protocol, sender);
    this.intervalMillis = intervalMillis;
  }

  private static ExporterId exporterId(OtlpConfig.Protocol protocol, OtlpSender sender) {
    return sender == null
        ? null
        : ExporterId.otlp(METRIC_DATA_POINT, protocol, sender.destination());
  }

  @VisibleForTesting
  OtlpSender getSender() {
    return sender;
  }

  @VisibleForTesting
  OtlpMetricsCollector getCollector() {
    return collector;
  }

  @VisibleForTesting
  ComponentId getReader() {
    return reader;
  }

  @VisibleForTesting
  ExporterId getExporter() {
    return exporter;
  }

  /** Starts periodic collection and export, reporting both to {@code healthMetrics}. */
  public void start(HealthMetrics healthMetrics) {
    if (sender == null) {
      return;
    }

    this.healthMetrics = healthMetrics;
    this.exportRecorder = ExportRecorder.create(healthMetrics, exporter);

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

    OtelMetricRegistry.INSTANCE.registerShutdownHook(
        () -> exportThenShutdown(FINAL_EXPORT_TIMEOUT_SECONDS, TimeUnit.SECONDS));
  }

  public void flush() {
    synchronized (lifecycleLock) {
      if (sender != null && shutdownLatch == null) {
        scheduler.execute(this::export);
      }
    }
  }

  /**
   * Requests a final export followed by OTLP metrics exporter shutdown. Waits up to the given
   * timeout for completion. Shutdown continues if the wait times out or the thread is interrupted.
   *
   * @param timeout the maximum time to wait for the export and shutdown to complete
   * @param unit the unit of the timeout
   * @return {@code true} if the export and shutdown completed; {@code false} if the wait timed out
   *     or the thread was interrupted. Completion does not indicate a successful export.
   */
  boolean exportThenShutdown(long timeout, TimeUnit unit) {
    CountDownLatch latch;
    synchronized (lifecycleLock) {
      if (shutdownLatch == null) {
        shutdownLatch = new CountDownLatch(1);
        scheduledTask.cancel();
        scheduler.execute(this::waitForExportThenShutdown);
      }
      latch = shutdownLatch;
    }

    try {
      return latch.await(timeout, unit);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  /** Shutdown on JVM exit; avoids I/O and any waiting or joining on threads. */
  public void shutdown() {
    if (scheduledTask != null) {
      scheduledTask.cancel();
    }
    if (sender != null) {
      closeSender();
    }
  }

  private void waitForExportThenShutdown() {
    export();
    closeSender();
    shutdownLatch.countDown();
  }

  private void closeSender() {
    try {
      sender.shutdown();
    } catch (Throwable e) {
      LOGGER.debug("Failed to shut down OTLP metrics sender", e);
    }
  }

  private void export() {
    ExportRecorder.Export export = null;
    try {
      OtlpPayload payload = collect();
      if (payload == OtlpPayload.EMPTY) {
        return;
      }

      OtlpTelemetry.getInstance().onMetricsExportAttempt();
      export = exportRecorder.start(payload.getItemCount());
      RemoteApi.Response response = sender.send(payload);
      export.finish(response);
      boolean success = response != null && response.success();
      OtlpTelemetry.getInstance().onMetricsExportComplete(success);
    } catch (Throwable e) {
      if (export != null) {
        export.failure(e);
        OtlpTelemetry.getInstance().onMetricsExportComplete(false);
      }
      LOGGER.debug("Failed to export OTLP metrics", e);
    }
  }

  /**
   * Collects the metrics, reporting how long it took and whether it failed.
   *
   * <p>The duration includes encoding the payload, which the collector does while it collects.
   */
  private OtlpPayload collect() {
    HealthMetrics sink = healthMetrics;
    long startNanos = System.nanoTime();
    Throwable error = null;
    try {
      return collector.collectMetrics();
    } catch (Throwable e) {
      error = e;
      throw e;
    } finally {
      // self-monitoring must never drop the payload or replace the collection error
      try {
        sink.onMetricCollection(reader, System.nanoTime() - startNanos, error);
      } catch (Throwable e) {
        LOGGER.debug("Failed to record OTLP metrics collection", e);
      }
    }
  }
}

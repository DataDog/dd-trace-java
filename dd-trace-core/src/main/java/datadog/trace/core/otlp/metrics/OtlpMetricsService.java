package datadog.trace.core.otlp.metrics;

import static datadog.trace.util.AgentThreadFactory.AgentThread.OTLP_METRICS_EXPORTER;

import datadog.trace.api.Config;
import datadog.trace.api.config.OtlpConfig;
import datadog.trace.api.telemetry.OtlpTelemetry;
import datadog.trace.api.time.SystemTimeSource;
import datadog.trace.common.writer.RemoteApi;
import datadog.trace.core.otlp.common.OtlpPayload;
import datadog.trace.core.otlp.common.OtlpSender;
import datadog.trace.util.AgentThreadFactory;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Periodic service to collect OpenTelemetry metrics and export them over OTLP. */
public final class OtlpMetricsService {
  private static final Logger LOGGER = LoggerFactory.getLogger(OtlpMetricsService.class);
  public static final OtlpMetricsService INSTANCE = new OtlpMetricsService(Config.get());

  private final ScheduledExecutorService executor;
  private final OtlpMetricsCollector collector;
  private final OtlpSender sender;
  private final int intervalMillis;
  private final Object lifecycleLock = new Object();

  private ScheduledFuture<?> scheduledTask;
  private CompletableFuture<Boolean> shutdownFuture;

  OtlpMetricsService(Config config) {
    this.executor =
        Executors.newSingleThreadScheduledExecutor(new AgentThreadFactory(OTLP_METRICS_EXPORTER));
    this.sender = OtlpMetricsSenderFactory.create(config);
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
      ScheduledExecutorService executor,
      OtlpMetricsCollector collector,
      OtlpSender sender,
      int intervalMillis) {
    this.executor = executor;
    this.collector = collector;
    this.sender = sender;
    this.intervalMillis = intervalMillis;
  }

  OtlpSender getSender() {
    return sender;
  }

  OtlpMetricsCollector getCollector() {
    return collector;
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

    synchronized (lifecycleLock) {
      if (shutdownFuture == null && scheduledTask == null) {
        scheduledTask =
            executor.scheduleAtFixedRate(
                this::export, initialMillis, intervalMillis, TimeUnit.MILLISECONDS);
      }
    }
  }

  public CompletableFuture<Boolean> forceFlush() {
    synchronized (lifecycleLock) {
      if (sender == null || shutdownFuture != null) {
        return CompletableFuture.completedFuture(false);
      }
      CompletableFuture<Boolean> result = new CompletableFuture<>();
      try {
        executor.execute(() -> result.complete(export()));
      } catch (RejectedExecutionException e) {
        LOGGER.debug("OTLP metrics executor rejected force flush", e);
        result.complete(false);
      }
      return result;
    }
  }

  public void flush() {
    forceFlush();
  }

  public CompletableFuture<Boolean> shutdown() {
    synchronized (lifecycleLock) {
      if (shutdownFuture != null) {
        return shutdownResult();
      }

      shutdownFuture = new CompletableFuture<>();
      if (scheduledTask != null) {
        scheduledTask.cancel(false);
      }
      if (sender == null) {
        executor.shutdown();
        shutdownFuture.complete(false);
        return shutdownResult();
      }

      try {
        executor.execute(this::finishShutdown);
      } catch (RejectedExecutionException e) {
        LOGGER.debug("OTLP metrics executor rejected shutdown", e);
        closeSender();
        executor.shutdown();
        shutdownFuture.complete(false);
      }
      return shutdownResult();
    }
  }

  private CompletableFuture<Boolean> shutdownResult() {
    return shutdownFuture.thenApply(result -> result);
  }

  private void finishShutdown() {
    boolean result = export();
    if (!closeSender()) {
      result = false;
    }
    try {
      executor.shutdown();
    } catch (Throwable e) {
      LOGGER.debug("Failed to shut down OTLP metrics executor", e);
      result = false;
    }
    shutdownFuture.complete(result);
  }

  private boolean closeSender() {
    try {
      sender.shutdown();
      return true;
    } catch (Throwable e) {
      LOGGER.debug("Failed to shut down OTLP metrics sender", e);
      return false;
    }
  }

  private boolean export() {
    boolean attempted = false;
    try {
      OtlpPayload payload = collector.collectMetrics();
      if (payload == OtlpPayload.EMPTY) {
        return true;
      }

      OtlpTelemetry.getInstance().onMetricsExportAttempt();
      attempted = true;
      RemoteApi.Response response = sender.send(payload);
      boolean success = response != null && response.success();
      OtlpTelemetry.getInstance().onMetricsExportComplete(success);
      return success;
    } catch (Throwable e) {
      if (attempted) {
        OtlpTelemetry.getInstance().onMetricsExportComplete(false);
      }
      LOGGER.debug("Failed to export OTLP metrics", e);
      return false;
    }
  }
}

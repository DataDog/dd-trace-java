package datadog.telemetry;

import datadog.telemetry.metric.MetricPeriodicAction;
import datadog.trace.api.Config;
import datadog.trace.api.ConfigCollector;
import datadog.trace.api.time.SystemTimeSource;
import datadog.trace.api.time.TimeSource;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TelemetryRunnable implements Runnable {

  private static final Logger log = LoggerFactory.getLogger(TelemetryRunnable.class);

  private final TelemetryService telemetryService;
  private final List<TelemetryPeriodicAction> actions;
  private final List<MetricPeriodicAction> actionsAtMetricsInterval;
  private final ThreadSleeper sleeper;

  private final TimeSource timeSource;
  private final long metricsIntervalMs;
  private final long heartbeatIntervalMs;
  private long nextMetricsIntervalMs;
  private long nextHeartbeatIntervalMs;

  public TelemetryRunnable(
      final TelemetryService telemetryService, final List<TelemetryPeriodicAction> actions) {
    this(telemetryService, actions, new ThreadSleeperImpl(), SystemTimeSource.INSTANCE);
  }

  TelemetryRunnable(
      final TelemetryService telemetryService,
      final List<TelemetryPeriodicAction> actions,
      final ThreadSleeper sleeper,
      final TimeSource timeSource) {
    this.telemetryService = telemetryService;
    this.actions = actions;
    this.actionsAtMetricsInterval = findMetricPeriodicActions(actions);
    this.sleeper = sleeper;
    this.timeSource = timeSource;
    this.metricsIntervalMs = (long) (Config.get().getTelemetryMetricsInterval() * 1000);
    this.heartbeatIntervalMs = (long) (Config.get().getTelemetryHeartbeatInterval() * 1000);
    this.nextMetricsIntervalMs = 0;
    this.nextHeartbeatIntervalMs = 0;
  }

  private List<MetricPeriodicAction> findMetricPeriodicActions(
      final List<TelemetryPeriodicAction> actions) {
    return actions.stream()
        .filter(MetricPeriodicAction.class::isInstance)
        .map(it -> (MetricPeriodicAction) it)
        .collect(Collectors.toList());
  }

  @Override
  public void run() {
    // Ensure that Config has been initialized, so ConfigCollector can collect all settings first.
    Config.get();

    final long currentTime = timeSource.getCurrentTimeMillis();
    nextMetricsIntervalMs = currentTime;
    nextHeartbeatIntervalMs = currentTime;

    while (!Thread.interrupted()) {
      try {
        mainLoopIteration();
        waitForNextIteration();
      } catch (InterruptedException e) {
        log.debug("Interrupted; finishing telemetry thread");
        Thread.currentThread().interrupt();
      }
    }

    telemetryService.sendAppClosingRequest();
    log.debug("Telemetry thread finished");
  }

  private void mainLoopIteration() throws InterruptedException {
    final long currentTime = timeSource.getCurrentTimeMillis();

    Map<String, Object> collectedConfig = ConfigCollector.get().collect();
    if (!collectedConfig.isEmpty()) {
      telemetryService.addConfiguration(collectedConfig);
    }

    // Collect request metrics every N seconds (default 10s)
    if (currentTime >= nextMetricsIntervalMs) {
      nextMetricsIntervalMs += metricsIntervalMs;
      for (MetricPeriodicAction action : actionsAtMetricsInterval) {
        action.collector().prepareMetrics();
      }
    }

    if (currentTime >= nextHeartbeatIntervalMs) {
      nextHeartbeatIntervalMs += heartbeatIntervalMs;
      for (final TelemetryPeriodicAction action : this.actions) {
        action.doIteration(this.telemetryService);
      }
      telemetryService.sendIntervalRequests();
    }
  }

  private void waitForNextIteration() {
    final long currentTime = timeSource.getCurrentTimeMillis();
    long nextIntervalMs = Math.min(nextMetricsIntervalMs, nextHeartbeatIntervalMs);
    if (currentTime >= nextIntervalMs) {
      // We are probably under high load, and the time to send telemetry has overrun the interval.
      // Accept the drift and skip one heartbeat.
      nextMetricsIntervalMs = currentTime + metricsIntervalMs;
      nextHeartbeatIntervalMs = currentTime + heartbeatIntervalMs;
      nextIntervalMs = Math.min(nextMetricsIntervalMs, nextHeartbeatIntervalMs);
      log.debug("Time to run telemetry actions exceeded the interval");
    }
    final long waitMs = nextIntervalMs - currentTime;
    sleeper.sleep(waitMs);
  }

  interface ThreadSleeper {
    void sleep(long timeoutMs);
  }

  private static class ThreadSleeperImpl implements ThreadSleeper {
    @Override
    public void sleep(long timeoutMs) {
      try {
        Thread.sleep(timeoutMs);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  public interface TelemetryPeriodicAction {
    void doIteration(TelemetryService service);
  }
}

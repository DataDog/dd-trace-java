package datadog.telemetry;

import datadog.telemetry.metric.MetricPeriodicAction;
import datadog.trace.api.Config;
import datadog.trace.api.ConfigCollector;
import datadog.trace.api.time.SystemTimeSource;
import datadog.trace.api.time.TimeSource;
import java.util.List;
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
  private long lastMetricsIntervalMs;
  private long lastHeartbeatIntervalMs;

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
    this.lastMetricsIntervalMs = 0;
    this.lastHeartbeatIntervalMs = 0;
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
    telemetryService.addConfiguration(ConfigCollector.get());

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
    // Collect request metrics every N seconds (default 10s)
    final long currentTime = timeSource.getCurrentTimeMillis();
    if (currentTime - lastMetricsIntervalMs >= metricsIntervalMs) {
      lastMetricsIntervalMs = currentTime;
      for (MetricPeriodicAction action : actionsAtMetricsInterval) {
        action.collector().prepareMetrics();
      }
    }

    if (currentTime - lastHeartbeatIntervalMs >= heartbeatIntervalMs) {
      lastHeartbeatIntervalMs = currentTime;
      for (final TelemetryPeriodicAction action : this.actions) {
        action.doIteration(this.telemetryService);
      }
      telemetryService.sendIntervalRequests();
    }
  }

  private void waitForNextIteration() {
    final long currentTime = timeSource.getCurrentTimeMillis();
    final long nextHeartbeatInterval = lastHeartbeatIntervalMs + heartbeatIntervalMs;
    final long nextMetricsInterval = lastMetricsIntervalMs + metricsIntervalMs;
    final long waitMs =
        Math.max(
            0, Math.min(nextHeartbeatInterval - currentTime, nextMetricsInterval - currentTime));
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

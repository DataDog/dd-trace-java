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
  private final Scheduler scheduler;

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
    this.scheduler =
        new Scheduler(
            timeSource,
            sleeper,
            (long) (Config.get().getTelemetryHeartbeatInterval() * 1000),
            (long) (Config.get().getTelemetryMetricsInterval() * 1000));
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

    scheduler.init();

    while (!Thread.interrupted()) {
      try {
        mainLoopIteration();
        scheduler.sleepUntilNextIteration();
      } catch (InterruptedException e) {
        log.debug("Interrupted; finishing telemetry thread");
        Thread.currentThread().interrupt();
      }
    }

    telemetryService.sendAppClosingRequest();
    log.debug("Telemetry thread finished");
  }

  private void mainLoopIteration() throws InterruptedException {
    Map<String, Object> collectedConfig = ConfigCollector.get().collect();
    if (!collectedConfig.isEmpty()) {
      telemetryService.addConfiguration(collectedConfig);
    }

    // Collect request metrics every N seconds (default 10s)
    if (scheduler.shouldRunMetrics()) {
      for (MetricPeriodicAction action : actionsAtMetricsInterval) {
        action.collector().prepareMetrics();
      }
    }

    if (scheduler.shouldRunHeartbeat()) {
      for (final TelemetryPeriodicAction action : this.actions) {
        action.doIteration(this.telemetryService);
      }
      telemetryService.sendIntervalRequests();
    }
  }

  interface ThreadSleeper {
    void sleep(long timeoutMs);
  }

  static class ThreadSleeperImpl implements ThreadSleeper {
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

  static class Scheduler {
    private final TimeSource timeSource;
    private final ThreadSleeper sleeper;
    private final long heartbeatIntervalMs;
    private final long metricsIntervalMs;
    private long nextHeartbeatIntervalMs;
    private long nextMetricsIntervalMs;
    private long currentTime;

    public Scheduler(
        final TimeSource timeSource,
        final ThreadSleeper sleeper,
        final long heartbeatIntervalMs,
        final long metricsIntervalMs) {
      this.timeSource = timeSource;
      this.sleeper = sleeper;
      this.heartbeatIntervalMs = heartbeatIntervalMs;
      this.metricsIntervalMs = metricsIntervalMs;
      nextHeartbeatIntervalMs = 0;
      nextMetricsIntervalMs = 0;
      currentTime = 0;
    }

    public void init() {
      final long currentTime = timeSource.getCurrentTimeMillis();
      this.currentTime = currentTime;
      nextMetricsIntervalMs = currentTime;
      nextHeartbeatIntervalMs = currentTime;
    }

    public boolean shouldRunMetrics() {
      if (currentTime >= nextMetricsIntervalMs) {
        nextMetricsIntervalMs += metricsIntervalMs;
        return true;
      }
      return false;
    }

    public boolean shouldRunHeartbeat() {
      if (currentTime >= nextHeartbeatIntervalMs) {
        nextHeartbeatIntervalMs += heartbeatIntervalMs;
        return true;
      }
      return false;
    }

    public void sleepUntilNextIteration() {
      currentTime = timeSource.getCurrentTimeMillis();

      if (currentTime >= nextHeartbeatIntervalMs) {
        // We are probably under high load or, more likely (?) slow network and the time to send
        // telemetry has overrun the interval. In this case, we reset metrics and heartbeat interval
        // to trigger them immediately.
        nextMetricsIntervalMs = currentTime;
        nextHeartbeatIntervalMs = currentTime;
        log.debug(
            "Time to run telemetry actions exceeded the interval, triggering the next iteration immediately");
        return;
      }

      while (currentTime >= nextMetricsIntervalMs) {
        // If metric collection exceeded the interval, something went really wrong. Either there's a
        // very short interval (not default), or metric collection is taking abnormally long. We
        // skip
        // intervals in this case.
        nextMetricsIntervalMs += metricsIntervalMs;
      }

      long nextIntervalMs = Math.min(nextMetricsIntervalMs, nextHeartbeatIntervalMs);
      final long waitMs = nextIntervalMs - currentTime;
      sleeper.sleep(waitMs);
      currentTime = timeSource.getCurrentTimeMillis();
    }
  }
}

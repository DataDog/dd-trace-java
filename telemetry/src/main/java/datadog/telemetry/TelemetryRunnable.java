package datadog.telemetry;

import datadog.telemetry.metric.MetricPeriodicAction;
import datadog.trace.api.Config;
import datadog.trace.api.ConfigCollector;
import datadog.trace.api.ConfigOrigin;
import datadog.trace.api.ConfigSetting;
import datadog.trace.api.time.SystemTimeSource;
import datadog.trace.api.time.TimeSource;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TelemetryRunnable implements Runnable {

  private static final Logger log = LoggerFactory.getLogger(TelemetryRunnable.class);
  private static final int APP_STARTED_RETRIES = 3;
  private static final int APP_STARTED_PAUSE_BETWEEN_RETRIES_MILLIS = 500;
  private static final int MAX_CONSECUTIVE_REQUESTS = 3;

  private final TelemetryService telemetryService;
  private final List<TelemetryPeriodicAction> actions;
  private final List<MetricPeriodicAction> actionsAtMetricsInterval;
  private final Scheduler scheduler;
  private boolean startupEventSent;

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
            (long) (Config.get().getTelemetryMetricsInterval() * 1000),
            Config.get().getTelemetryExtendedHeartbeatInterval() * 1000);
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

    collectConfigChanges();

    scheduler.init();

    while (!Thread.interrupted()) {
      try {
        if (!startupEventSent) {
          startupEventSent = sendAppStartedEvent();
        }
        if (startupEventSent) {
          mainLoopIteration();
        } else {
          // wait until next heartbeat interval before reattempting startup event
          scheduler.shouldRunHeartbeat();
        }
        scheduler.sleepUntilNextIteration();
      } catch (InterruptedException e) {
        log.debug("Interrupted; finishing telemetry thread");
        Thread.currentThread().interrupt();
      }
    }

    if (startupEventSent) {
      flushPendingTelemetryData();
      telemetryService.sendAppClosingEvent();
    }
    log.debug("Telemetry thread finished");
  }

  /**
   * Attempts to send an app-started event.
   *
   * @return `true` - if attempt was successful and `false` otherwise
   */
  private boolean sendAppStartedEvent() throws InterruptedException {
    int attempt = 0;
    while (attempt < APP_STARTED_RETRIES && !telemetryService.sendAppStartedEvent()) {
      attempt += 1;
      log.debug(
          "Couldn't send an app-started event on {} attempt out of {}.",
          attempt,
          APP_STARTED_RETRIES);
      // Sleep between retries to allow OkHttp to release a non-daemon writer thread that would
      // otherwise prevent the application from exiting.
      Thread.sleep(APP_STARTED_PAUSE_BETWEEN_RETRIES_MILLIS);
    }
    return attempt < APP_STARTED_RETRIES;
  }

  private void mainLoopIteration() throws InterruptedException {
    collectConfigChanges();

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
      for (int i = 0; i < MAX_CONSECUTIVE_REQUESTS; i++) {
        if (!telemetryService.sendTelemetryEvents()) {
          // stop if there is no more data to be sent, or it failed to send a request
          break;
        }
      }
    }

    if (scheduler.shouldRunExtendedHeartbeat()) {
      if (telemetryService.sendExtendedHeartbeat()) {
        // advance next extended-heartbeat only if request was successful, otherwise reattempt it
        // next heartbeat interval
        scheduler.scheduleNextExtendedHeartbeat();
      }
    }
  }

  private void collectConfigChanges() {
    Map<ConfigOrigin, Map<String, ConfigSetting>> collectedConfig = ConfigCollector.get().collect();
    if (!collectedConfig.isEmpty()) {
      telemetryService.addConfiguration(collectedConfig);
    }
  }

  private void flushPendingTelemetryData() {
    collectConfigChanges();

    for (MetricPeriodicAction action : actionsAtMetricsInterval) {
      action.collector().prepareMetrics();
    }

    for (final TelemetryPeriodicAction action : actions) {
      action.doIteration(telemetryService);
    }
    telemetryService.sendTelemetryEvents();
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
    private final long extendedHeartbeatIntervalMs;
    private final long metricsIntervalMs;
    private long nextHeartbeatIntervalMs;
    private long nextExtendedHeartbeatIntervalMs;
    private long nextMetricsIntervalMs;
    private long currentTime;

    public Scheduler(
        final TimeSource timeSource,
        final ThreadSleeper sleeper,
        final long heartbeatIntervalMs,
        final long metricsIntervalMs,
        final long extendedHeartbeatIntervalMs) {
      this.timeSource = timeSource;
      this.sleeper = sleeper;
      this.heartbeatIntervalMs = heartbeatIntervalMs;
      this.metricsIntervalMs = metricsIntervalMs;
      this.extendedHeartbeatIntervalMs = extendedHeartbeatIntervalMs;
      nextHeartbeatIntervalMs = 0;
      nextMetricsIntervalMs = 0;
      nextExtendedHeartbeatIntervalMs = 0;
      currentTime = 0;
    }

    public void init() {
      final long currentTime = timeSource.getCurrentTimeMillis();
      this.currentTime = currentTime;
      nextMetricsIntervalMs = currentTime;
      nextHeartbeatIntervalMs = currentTime;
      scheduleNextExtendedHeartbeat();
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

    public boolean shouldRunExtendedHeartbeat() {
      return currentTime >= nextExtendedHeartbeatIntervalMs;
    }

    public void scheduleNextExtendedHeartbeat() {
      // schedule very first extended-heartbeat only after interval elapses
      nextExtendedHeartbeatIntervalMs = currentTime + extendedHeartbeatIntervalMs;
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
        // skip intervals in this case.
        nextMetricsIntervalMs += metricsIntervalMs;
      }

      long nextIntervalMs = Math.min(nextMetricsIntervalMs, nextHeartbeatIntervalMs);
      final long waitMs = nextIntervalMs - currentTime;
      sleeper.sleep(waitMs);
      currentTime = timeSource.getCurrentTimeMillis();
    }
  }
}

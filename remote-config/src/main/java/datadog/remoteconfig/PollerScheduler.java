package datadog.remoteconfig;

import datadog.trace.api.Config;
import datadog.trace.util.AgentTaskScheduler;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Handles scheduling scheme for polling configuration */
class PollerScheduler {
  private static final Logger LOGGER = LoggerFactory.getLogger(PollerScheduler.class);

  private final long initialPollInterval;
  private long currentPollInterval;
  private final DefaultConfigurationPoller poller;
  private final AgentTaskScheduler taskScheduler;
  private volatile AgentTaskScheduler.Scheduled<ConfigurationPoller> scheduled;

  public PollerScheduler(
      Config config, DefaultConfigurationPoller poller, AgentTaskScheduler taskScheduler) {
    // TODO add a jitter to avoid herd issue
    this.initialPollInterval = (long) (config.getRemoteConfigPollIntervalSeconds() * 1000);
    this.poller = poller;
    this.taskScheduler = taskScheduler;
  }

  void start() {
    currentPollInterval = initialPollInterval;
    reschedule();
  }

  void stop() {
    AgentTaskScheduler.Scheduled<ConfigurationPoller> localScheduled = this.scheduled;
    if (localScheduled != null) {
      localScheduled.cancel();
    }
  }

  long getInitialPollInterval() {
    return initialPollInterval;
  }

  void reschedule(long newInterval) {
    if (currentPollInterval != newInterval) {
      LOGGER.debug("Setting polling interval to {}ms, and rescheduling", newInterval);
      currentPollInterval = newInterval;
      reschedule();
    }
  }

  private void reschedule() {
    AgentTaskScheduler.Scheduled<ConfigurationPoller> localScheduled = this.scheduled;
    long initialDelay = currentPollInterval;
    if (localScheduled != null) {
      localScheduled.cancel();
    } else {
      initialDelay = 0;
    }
    this.scheduled =
        taskScheduler.scheduleAtFixedRate(
            poller::poll, poller, initialDelay, currentPollInterval, TimeUnit.MILLISECONDS);
  }
}

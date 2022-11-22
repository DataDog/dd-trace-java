package datadog.telemetry;

import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.trace.api.Config;
import datadog.trace.api.ConfigCollector;
import java.util.List;
import okhttp3.HttpUrl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TelemetryRunnable implements Runnable {

  private static final Logger log = LoggerFactory.getLogger(TelemetryRunnable.class);

  private final AgentDiscoverer discoverer;
  private final TelemetryService telemetryService;

  private final long heartbeatIntervalMs;
  private final List<TelemetryPeriodicAction> actions;
  private final ThreadSleeper sleeper;

  public TelemetryRunnable(
      AgentDiscoverer discoverer,
      TelemetryService telemetryService,
      int heartbeatIntervalMs,
      List<TelemetryPeriodicAction> actions) {
    this(discoverer, telemetryService, heartbeatIntervalMs, actions, new ThreadSleeperImpl());
  }

  TelemetryRunnable(
      AgentDiscoverer discoverer,
      TelemetryService telemetryService,
      int heartbeatIntervalMs,
      List<TelemetryPeriodicAction> actions,
      ThreadSleeper sleeper) {
    this.discoverer = discoverer;
    this.telemetryService = telemetryService;
    this.heartbeatIntervalMs = heartbeatIntervalMs;
    this.actions = actions;
    this.sleeper = sleeper;
  }

  @Override
  public void run() {
    // Ensure that Config has been initialized, so ConfigCollector can collect all settings first.
    Config.get();

    log.debug("Sending AppStarted telemetry event");
    this.telemetryService.addConfiguration(ConfigCollector.get());

    for (TelemetryPeriodicAction action : this.actions) {
      action.doIteration(this.telemetryService);
    }

    RequestBuilder requestBuilder = discoverer.telemetryRequestBuilder();

    RequestStatus status = telemetryService.sendAppStarted(requestBuilder);
    RequestStatus lastStatus = RequestStatus.SUCCESS;

    while (!Thread.interrupted()) {

      sleeper.sleep(heartbeatIntervalMs);

      for (TelemetryPeriodicAction action : this.actions) {
        action.doIteration(this.telemetryService);
      }

      if (status == RequestStatus.ENDPOINT_ERROR || requestBuilder == null) {
        requestBuilder = discoverer.telemetryRequestBuilder();
      }

      status = telemetryService.sendTelemetry(requestBuilder);
      switch (status) {
        case ENDPOINT_ERROR:
          if (status != lastStatus) {
            log.warn(
                "Unable to locate DD Agent with supported telemetry; will lookup every {} seconds.",
                heartbeatIntervalMs / 1000);
          }
          break;
        case HTTP_ERROR:
          if (status != lastStatus) {
            log.warn(
                "Last attempt to send telemetry failed; will continue retrying every {} seconds.",
                heartbeatIntervalMs / 1000);
          }
          break;
        case SUCCESS:
          if (status != lastStatus) {
            log.info("Telemetry back to normal - message sent successfully");
          } else {
            log.debug("Telemetry message sent successfully");
          }
      }
      lastStatus = status;
    }

    log.debug("Sending AppClosing telemetry event");
    telemetryService.sendAppClosing(requestBuilder);
    log.debug("Telemetry thread finishing");
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

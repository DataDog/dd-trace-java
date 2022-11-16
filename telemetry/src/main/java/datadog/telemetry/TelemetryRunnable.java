package datadog.telemetry;

import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.telemetry.api.RequestType;
import datadog.trace.api.Config;
import datadog.trace.api.ConfigCollector;
import java.io.IOException;
import java.util.List;
import java.util.Queue;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TelemetryRunnable implements Runnable {
  private static final double BACKOFF_INITIAL = 3.0d;
  private static final double BACKOFF_BASE = 3.0d;
  private static final double BACKOFF_MAX_EXPONENT = 3.0d;

  private static final Logger log = LoggerFactory.getLogger(TelemetryRunnable.class);

  private final OkHttpClient okHttpClient;
  private final TelemetryService telemetryService;
  private final List<TelemetryPeriodicAction> actions;
  private final ThreadSleeper sleeper;

  private int consecutiveFailures;

  private final DiscoveryRequestBuilderSupplier discoveryRequestBuilderSupplier;

  private boolean agentFailure = false;
  private boolean httpFailure = false;

  public TelemetryRunnable(
      SharedCommunicationObjects sco,
      TelemetryService telemetryService,
      List<TelemetryPeriodicAction> actions) {
    this(sco, telemetryService, actions, new ThreadSleeperImpl());
  }

  TelemetryRunnable(
      SharedCommunicationObjects sco,
      TelemetryService telemetryService,
      List<TelemetryPeriodicAction> actions,
      ThreadSleeper sleeper) {
    this.okHttpClient = sco.okHttpClient;
    this.telemetryService = telemetryService;
    this.actions = actions;
    this.sleeper = sleeper;
    this.discoveryRequestBuilderSupplier = new DiscoveryRequestBuilderSupplier(sco);
  }

  @Override
  public void run() {
    // Ensure that Config has been initialized, so ConfigCollector can collect all settings first.
    Config.get();

    log.debug("Adding APP_STARTED telemetry event");
    this.telemetryService.addConfiguration(ConfigCollector.get());
    for (TelemetryPeriodicAction action : this.actions) {
      action.doIteration(this.telemetryService);
    }

    this.telemetryService.addStartedRequest();

    while (!Thread.interrupted()) {
      try {
        RequestStatus status = mainLoopIteration();
        switch (status) {
          case SUCCESS:
          case NOTING_TO_SEND:
            successWait();
            break;
          case NOT_SUPPORTED_ERROR:
            agentFailureWait();
            break;
          case HTTP_ERROR:
            httpFailureWait();
            break;
        }
      } catch (InterruptedException e) {
        log.debug("Interrupted; finishing telemetry thread");
        Thread.currentThread().interrupt();
      }
    }

    log.debug("Sending APP_CLOSING telemetry event");
    // Instantly send APP_CLOSING event if possible
    RequestBuilder requestBuilder = discoveryRequestBuilderSupplier.get();
    if (requestBuilder != null) {
      Request request = requestBuilder.build(RequestType.APP_CLOSING, null);
      sendRequest(request);
    }
    log.debug("Telemetry thread finishing");
  }

  private RequestStatus mainLoopIteration() throws InterruptedException {
    for (TelemetryPeriodicAction action : this.actions) {
      action.doIteration(this.telemetryService);
    }

    Queue<TelemetryData> queue = telemetryService.prepareRequests();
    if (queue.isEmpty()) {
      return RequestStatus.NOTING_TO_SEND;
    }

    return sendTelemetry(queue);
  }

  private void successWait() {
    consecutiveFailures = 0;
    if (agentFailure) {
      log.info("Discovered DD Agent with supported telemetry");
    }
    agentFailure = false;
    httpFailure = false;
    int waitMs = telemetryService.getHeartbeatInterval();

    // Wait between iterations no longer than 10 seconds
    if (waitMs > 10000) waitMs = 10000;

    sleeper.sleep(waitMs);
  }

  private void agentFailureWait() {
    long waitSeconds = 2 * 60; // 2 minutes
    if (!agentFailure) {
      log.warn(
          "Unable to locate DD Agent with supported telemetry. We will lookup every {} sec.",
          waitSeconds);
      agentFailure = true;
    }

    sleeper.sleep(waitSeconds * 1000);
  }

  private void httpFailureWait() {
    consecutiveFailures++;
    double waitSeconds =
        BACKOFF_INITIAL
            * Math.pow(
                BACKOFF_BASE, Math.min((double) consecutiveFailures - 1, BACKOFF_MAX_EXPONENT));

    if (!httpFailure) {
      httpFailure = true;
    }

    // Reached max limit of attempts
    if (consecutiveFailures > BACKOFF_MAX_EXPONENT) {
      log.warn("Unable to send telemetry, giving up and drop telemetry. We will try later");
      // Too many failures - giving up and drop queue
      telemetryService.prepareRequests().clear();
      // Try re-discover DD Agent
      discoveryRequestBuilderSupplier.needRediscover();
      consecutiveFailures = 0;
    } else {
      log.warn(
          "Last attempt to send telemetry failed; will retry in {} seconds (num failures: {})",
          waitSeconds,
          consecutiveFailures);
    }
    sleeper.sleep((long) (waitSeconds * 1000L));
  }

  private RequestStatus sendTelemetry(Queue<TelemetryData> queue) {
    RequestBuilder requestBuilder = discoveryRequestBuilderSupplier.get();
    if (requestBuilder == null) {
      // No telemetry endpoint - drop queue
      queue.clear();
      return RequestStatus.NOT_SUPPORTED_ERROR;
    }

    TelemetryData data;
    while ((data = queue.peek()) != null) {
      Request request = requestBuilder.build(data.getRequestType(), data.getPayload());
      if (!sendRequest(request)) {
        return RequestStatus.HTTP_ERROR;
      }
      // remove request from queue, in case of success submitting
      // we are the only consumer, so this is guaranteed to be the same we peeked
      queue.poll();
    }
    return RequestStatus.SUCCESS;
  }

  private boolean sendRequest(Request request) {
    try (Response response = okHttpClient.newCall(request).execute()) {
      if (response.code() != 202) {
        if (!httpFailure) {
          String msg =
              "Unexpected response from Telemetry Intake Service: "
                  + response.code()
                  + " "
                  + response.message();
          log.warn(msg);
        }
        discoveryRequestBuilderSupplier.needRediscover();
        return false;
      }
    } catch (IOException e) {
      if (!httpFailure) {
        // To prevent spamming - log exception only first time
        log.warn("IOException on HTTP request to Telemetry Intake Service", e);
      }
      return false;
    }

    log.debug("Telemetry message sent successfully");
    return true;
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

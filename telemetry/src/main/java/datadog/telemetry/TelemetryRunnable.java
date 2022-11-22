package datadog.telemetry;

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

  private SendResult lastStatus;
  private int consecutiveFailures;

  public TelemetryRunnable(
      OkHttpClient okHttpClient,
      TelemetryService telemetryService,
      List<TelemetryPeriodicAction> actions) {
    this(okHttpClient, telemetryService, actions, new ThreadSleeperImpl());
  }

  TelemetryRunnable(
      OkHttpClient okHttpClient,
      TelemetryService telemetryService,
      List<TelemetryPeriodicAction> actions,
      ThreadSleeper sleeper) {
    this.okHttpClient = okHttpClient;
    this.telemetryService = telemetryService;
    this.actions = actions;
    this.sleeper = sleeper;
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
        boolean success = mainLoopIteration();
        if (success) {
          successWait();
        } else {
          failureWait();
        }
      } catch (InterruptedException e) {
        log.debug("Interrupted; finishing telemetry thread");
        Thread.currentThread().interrupt();
      }
    }

    log.debug("Sending APP_CLOSING telemetry event");
    sendRequest(this.telemetryService.appClosingRequest());
    log.debug("Telemetry thread finishing");
  }

  private boolean mainLoopIteration() throws InterruptedException {
    for (TelemetryPeriodicAction action : this.actions) {
      action.doIteration(this.telemetryService);
    }

    Queue<Request> queue = telemetryService.prepareRequests();
    Request request;
    while ((request = queue.peek()) != null) {
      final SendResult result = sendRequest(request);
      lastStatus = result;
      if (result == SendResult.DROP) {
        // If we need to drop, clear the queue and return as if it was success.
        // We will not retry if the telemetry endpoint is disabled.
        queue.clear();
        return true;
      } else if (result == SendResult.FAILURE) {
        return false;
      }
      // remove request from queue, in case of success submitting
      // we are the only consumer, so this is guaranteed to be the same we peeked
      queue.poll();
    }
    return true;
  }

  private void successWait() {
    consecutiveFailures = 0;
    final int waitMs = telemetryService.getHeartbeatInterval();
    sleeper.sleep(waitMs);
  }

  private void failureWait() {
    consecutiveFailures++;
    double waitSeconds =
        BACKOFF_INITIAL
            * Math.pow(
                BACKOFF_BASE, Math.min((double) consecutiveFailures - 1, BACKOFF_MAX_EXPONENT));
    log.debug(
        "Last attempt to send telemetry failed; will retry in {} seconds (num failures: {})",
        waitSeconds,
        consecutiveFailures);
    sleeper.sleep((long) (waitSeconds * 1000L));
  }

  private SendResult sendRequest(Request request) {
    Response response;
    try {
      response = okHttpClient.newCall(request).execute();
    } catch (IOException e) {
      if (lastStatus != SendResult.FAILURE) {
        log.warn("IOException on HTTP request to Telemetry Intake Service: {}", e.toString());
      } else {
        log.debug("IOException on HTTP request to Telemetry Intake Service: {}", e.toString());
      }
      return SendResult.FAILURE;
    }

    if (response.code() == 404) {
      log.debug("Telemetry endpoint is disabled, dropping message");
      return SendResult.DROP;
    }
    if (response.code() != 202) {
      if (lastStatus != SendResult.FAILURE) {
        log.warn(
            "Telemetry Intake Service responded with: {} {} ", response.code(), response.message());
      } else {
        log.debug(
            "Telemetry Intake Service responded with: {} {} ", response.code(), response.message());
      }
      return SendResult.FAILURE;
    }

    log.debug("Telemetry message sent successfully");
    return SendResult.SUCCESS;
  }

  enum SendResult {
    SUCCESS,
    FAILURE,
    DROP;
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

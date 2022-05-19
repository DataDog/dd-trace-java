package datadog.telemetry;

import java.io.IOException;
import java.util.List;
import java.util.Queue;

import datadog.trace.api.ConfigCollector;
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
  private final TelemetryServiceImpl telemetryService;
  private final List<TelemetryPeriodicAction> actions;
  private final ThreadSleeper sleeper;

  private int consecutiveFailures;

  public TelemetryRunnable(
      OkHttpClient okHttpClient,
      TelemetryServiceImpl telemetryService,
      List<TelemetryPeriodicAction> actions) {
    this(okHttpClient, telemetryService, actions, new ThreadSleeperImpl());
  }

  TelemetryRunnable(
      OkHttpClient okHttpClient,
      TelemetryServiceImpl telemetryService,
      List<TelemetryPeriodicAction> actions,
      ThreadSleeper sleeper) {
    this.okHttpClient = okHttpClient;
    this.telemetryService = telemetryService;
    this.actions = actions;
    this.sleeper = sleeper;
  }

  @Override
  public void run() {
    log.info("Adding APP_STARTED telemetry event");
    this.telemetryService.addConfiguration(ConfigCollector.get());
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
        log.info("Interrupted; finishing telemetry thread");
        Thread.currentThread().interrupt();
      }
    }

    log.info("Sending APP_CLOSING telemetry event");
    sendRequest(this.telemetryService.appClosingRequest());
    log.info("Telemetry thread finishing");
  }

  private boolean mainLoopIteration() throws InterruptedException {
    for (TelemetryPeriodicAction action : this.actions) {
      action.doIteration(this.telemetryService);
    }

    Queue<Request> queue = telemetryService.prepareRequests();
    Request request;
    while ((request = queue.peek()) != null) {
      if (!sendRequest(request)) {
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
    int waitSeconds = 10;
    sleeper.sleep(waitSeconds * 1000L);
  }

  private void failureWait() {
    consecutiveFailures++;
    double waitSeconds =
        BACKOFF_INITIAL
            * Math.pow(
                BACKOFF_BASE, Math.min((double) consecutiveFailures - 1, BACKOFF_MAX_EXPONENT));
    log.warn(
        "Last attempt to send telemetry failed; will retry in {} seconds (num failures: {})",
        waitSeconds,
        consecutiveFailures);
    sleeper.sleep((long) (waitSeconds * 1000L));
  }

  private boolean sendRequest(Request request) {
    Response response;
    try {
      response = okHttpClient.newCall(request).execute();
    } catch (IOException e) {
      log.warn("IOException on HTTP request to Telemetry Intake Service", e);
      return false;
    }

    if (response.code() != 202) {
      log.warn(
          "Telemetry Intake Service responded with: " + response.code() + " " + response.message());
      return false;
    }
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

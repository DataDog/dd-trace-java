package datadog.communication.http;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A policy which encapsulates retry rules for HTTP calls. Whether to retry and how long to wait
 * before the next attempt is determined by received HTTP response.
 *
 * <p>Instances of this class are not thread-safe and not reusable: each HTTP call requires its own
 * instance.
 */
@NotThreadSafe
public class HttpRetryPolicy {

  private static final Logger log = LoggerFactory.getLogger(HttpRetryPolicy.class);

  private static final int NO_RESPONSE_RECEIVED = -1;
  private static final int TOO_MANY_REQUESTS_HTTP_CODE = 429;
  private static final String X_RATELIMIT_RESET_HTTP_HEADER = "x-ratelimit-reset";
  private static final int MAX_ALLOWED_WAIT_TIME_SECONDS = 10;
  private static final int RATE_LIMIT_DELAY_RANDOM_COMPONENT_MAX_MILLIS = 401;

  private int retriesLeft;
  private long delay;
  private final double delayFactor;

  private HttpRetryPolicy(int retriesLeft, long delay, double delayFactor) {
    this.retriesLeft = retriesLeft;
    this.delay = delay;
    this.delayFactor = delayFactor;
  }

  public boolean shouldRetry(@Nullable okhttp3.Response response) {
    if (retriesLeft == 0) {
      return false;
    }

    int responseCode = response != null ? response.code() : NO_RESPONSE_RECEIVED;
    if (responseCode == TOO_MANY_REQUESTS_HTTP_CODE) {
      retriesLeft = 0;

      String waitTime = response.header(X_RATELIMIT_RESET_HTTP_HEADER);
      if (waitTime == null) {
        return false;
      }

      long waitTimeSeconds;
      try {
        waitTimeSeconds = Long.parseLong(waitTime);
      } catch (NumberFormatException e) {
        log.error(
            "Could not parse " + X_RATELIMIT_RESET_HTTP_HEADER + " header contents: " + waitTime,
            e);
        return false;
      }

      if (waitTimeSeconds > MAX_ALLOWED_WAIT_TIME_SECONDS) {
        return false;
      }

      delay =
          TimeUnit.SECONDS.toMillis(waitTimeSeconds)
              + ThreadLocalRandom.current().nextInt(RATE_LIMIT_DELAY_RANDOM_COMPONENT_MAX_MILLIS);
      return true;

    } else if (responseCode >= 500 || responseCode == NO_RESPONSE_RECEIVED) {
      retriesLeft--;
      return true;

    } else {
      return false;
    }
  }

  public long backoff() {
    long currentDelay = delay;
    delay *= delayFactor;
    return currentDelay;
  }

  public static final class Factory {
    private final int maxRetries;
    private final long initialDelay;
    private final double delayFactor;

    public Factory(int maxRetries, int initialDelay, double delayFactor) {
      this.maxRetries = maxRetries;
      this.initialDelay = initialDelay;
      this.delayFactor = delayFactor;
    }

    public HttpRetryPolicy create() {
      return new HttpRetryPolicy(maxRetries, initialDelay, delayFactor);
    }
  }
}

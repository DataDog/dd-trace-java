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
 * <p>Logic is the following:
 *
 * <ul>
 *   <li>if there was no response, or response code is <b>5XX</b>, wait for specified delay time and
 *       retry (there is exponential backoff, so each consecutive retry takes longer than the
 *       previous one). Maximum number of retries and delay multiplication factor are provided
 *       during policy instantiation
 *   <li>if response code is <b>429 (Too Many Requests)</b>, try to get wait time from
 *       <b>x-ratelimit-reset</b> response header. Depending on the result:
 *       <ul>
 *         <li>if time is less than 10 seconds, wait for the specified amount + random(0, 0.4sec)
 *             (there will be only one retry in this case)
 *         <li>if time is more than 10 seconds, do not retry
 *         <li>if time was not provided or could not be parsed, do a regular retry (same logic as
 *             for 5XX responses)
 *       </ul>
 *   <li>in all other cases do not retry
 * </ul>
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
  private static final int RATE_LIMIT_RESET_TIME_UNDEFINED = -1;
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
      long waitTimeSeconds = getRateLimitResetTime(response);
      if (waitTimeSeconds == RATE_LIMIT_RESET_TIME_UNDEFINED) {
        retriesLeft--; // doing a regular retry if proper reset time was not provided
        return true;
      }

      if (waitTimeSeconds > MAX_ALLOWED_WAIT_TIME_SECONDS) {
        return false; // too long to wait, will not retry
      }

      retriesLeft = 0;
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

  private long getRateLimitResetTime(okhttp3.Response response) {
    String rateLimitHeader = response.header(X_RATELIMIT_RESET_HTTP_HEADER);
    if (rateLimitHeader == null) {
      return RATE_LIMIT_RESET_TIME_UNDEFINED;
    }

    try {
      return Long.parseLong(rateLimitHeader);
    } catch (NumberFormatException e) {
      log.error(
          "Could not parse "
              + X_RATELIMIT_RESET_HTTP_HEADER
              + " header contents: "
              + rateLimitHeader,
          e);
      return RATE_LIMIT_RESET_TIME_UNDEFINED;
    }
  }

  public long backoff() {
    long currentDelay = delay;
    delay = (long) (delay * delayFactor);
    return currentDelay;
  }

  public static class Factory {
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

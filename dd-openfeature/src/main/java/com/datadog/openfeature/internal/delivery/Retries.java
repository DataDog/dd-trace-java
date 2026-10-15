package com.datadog.openfeature.internal.delivery;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.http.HttpResponse;
import java.util.function.BooleanSupplier;
import javax.annotation.Nullable;

/** Sends HTTP requests, retrying the failures that can succeed on a later attempt. */
final class Retries {
  private Retries() {}

  /**
   * Sends a request until it gets a non-retryable response, the attempts are exhausted, or the
   * caller gives up.
   *
   * @param callFactory creates a new call for each attempt.
   * @param policy the retry rules and delays.
   * @param maxAttempts the maximum number of attempts.
   * @param sleeper the sleeper waiting between attempts.
   * @param cancelled whether the caller gave up on the request.
   * @param <T> the response body type.
   * @return the last response, which can still have a retryable status.
   * @throws IOException if the last attempt failed.
   * @throws InterruptedException if interrupted while sending or waiting between attempts.
   */
  static <T> HttpResponse<T> send(
      final CallFactory<T> callFactory,
      final Policy policy,
      final int maxAttempts,
      final Sleeper sleeper,
      final BooleanSupplier cancelled)
      throws IOException, InterruptedException {
    for (int attempt = 1; ; attempt++) {
      if (cancelled.getAsBoolean()) {
        throw new IOException("HTTP request cancelled");
      }
      if (Thread.interrupted()) {
        throw new InterruptedException();
      }
      long delayMillis;
      try {
        final HttpResponse<T> response = callFactory.newCall().execute();
        if (attempt == maxAttempts || cancelled.getAsBoolean()) {
          return response;
        }
        delayMillis = policy.retryDelayMillis(response, attempt);
        if (delayMillis < 0) {
          return response;
        }
      } catch (final IOException e) {
        if (attempt == maxAttempts
            || !policy.shouldRetry(e)
            || cancelled.getAsBoolean()
            || Thread.currentThread().isInterrupted()) {
          throw e;
        }
        delayMillis = policy.retryDelayMillis(null, attempt);
        if (delayMillis < 0) {
          throw e;
        }
      }
      sleeper.sleep(delayMillis);
    }
  }

  /**
   * Identifies retryable CDN responses.
   *
   * @param status the HTTP response status.
   * @return whether a request that got this status can succeed on a later attempt.
   */
  static boolean isRetryableStatus(final int status) {
    return status == HttpURLConnection.HTTP_CLIENT_TIMEOUT
        || status == 429
        || (status >= 500 && status <= 599);
  }

  @FunctionalInterface
  interface CallFactory<T> {
    HttpCall<T> newCall() throws IOException;
  }

  @FunctionalInterface
  interface Policy {
    /**
     * Computes the delay before retrying an attempt.
     *
     * @param response the failed response, or {@code null} for an I/O failure.
     * @param attempt the failed attempt number.
     * @return the delay in milliseconds, or a negative value to stop retrying.
     */
    long retryDelayMillis(@Nullable HttpResponse<?> response, int attempt);

    default boolean shouldRetry(final IOException error) {
      return true;
    }
  }

  @FunctionalInterface
  interface Sleeper {
    void sleep(long delayMillis) throws InterruptedException;
  }
}

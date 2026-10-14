package com.datadog.openfeature.internal.delivery;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.function.BooleanSupplier;
import java.util.function.IntToLongFunction;

/** Sends HTTP requests, retrying the failures that can succeed on a later attempt. */
final class Retries {
  private Retries() {}

  /**
   * Sends a request until it gets a non-retryable response, the attempts are exhausted, or the
   * caller gives up.
   *
   * @param httpClient the client to send the request with.
   * @param request the request to send.
   * @param bodyHandler the response body handler.
   * @param maxAttempts the maximum number of attempts.
   * @param retryDelayMillis the delay before the next attempt, given the failed attempt number.
   * @param sleeper the sleeper waiting between attempts.
   * @param cancelled whether the caller gave up on the request.
   * @param <T> the response body type.
   * @return the last response, which can still have a retryable status.
   * @throws IOException if the last attempt failed.
   * @throws InterruptedException if interrupted while sending or waiting between attempts.
   */
  static <T> HttpResponse<T> send(
      final HttpClient httpClient,
      final HttpRequest request,
      final HttpResponse.BodyHandler<T> bodyHandler,
      final int maxAttempts,
      final IntToLongFunction retryDelayMillis,
      final Sleeper sleeper,
      final BooleanSupplier cancelled)
      throws IOException, InterruptedException {
    int attempt = 0;
    while (true) {
      attempt++;
      try {
        final HttpResponse<T> response = httpClient.send(request, bodyHandler);
        if (!isRetryableStatus(response.statusCode())
            || attempt == maxAttempts
            || cancelled.getAsBoolean()) {
          return response;
        }
      } catch (final IOException e) {
        if (attempt == maxAttempts
            || cancelled.getAsBoolean()
            || Thread.currentThread().isInterrupted()) {
          throw e;
        }
      }
      sleeper.sleep(retryDelayMillis.applyAsLong(attempt));
    }
  }

  /**
   * @param status the HTTP response status.
   * @return whether a request that got this status can succeed on a later attempt.
   */
  static boolean isRetryableStatus(final int status) {
    return status == HttpURLConnection.HTTP_CLIENT_TIMEOUT
        || status == 429
        || (status >= 500 && status <= 599);
  }

  @FunctionalInterface
  interface Sleeper {
    void sleep(long delayMillis) throws InterruptedException;
  }
}

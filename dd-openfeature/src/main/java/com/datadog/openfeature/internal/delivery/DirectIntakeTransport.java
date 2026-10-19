package com.datadog.openfeature.internal.delivery;

import static java.net.http.HttpClient.Redirect.NEVER;
import static java.net.http.HttpRequest.BodyPublishers.ofByteArray;

import com.datadog.openfeature.internal.bridge.EventTransport;
import com.datadog.openfeature.internal.config.Settings;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

/** Posts events directly to the Datadog event platform intake, authenticated with an API key. */
public final class DirectIntakeTransport implements EventTransport {
  static final int MAX_RETRIES = 5;
  private static final long INITIAL_RETRY_DELAY_MILLIS = 100;
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

  private final URI intake;
  private final String apiKey;
  private final String traceId;
  private final HttpClient httpClient;
  private final Retries.Sleeper retrySleeper;

  DirectIntakeTransport(
      final URI intake,
      final String apiKey,
      final HttpClient httpClient,
      final Retries.Sleeper retrySleeper) {
    this.intake = intake;
    this.apiKey = apiKey;
    // Intake requests are attributed to a stable, random trace id.
    this.traceId = Long.toUnsignedString(ThreadLocalRandom.current().nextLong() >>> 1);
    this.httpClient = httpClient;
    this.retrySleeper = retrySleeper;
  }

  /**
   * Creates a direct intake transport.
   *
   * @param settings the SDK settings.
   * @return the transport, or {@code null} if no API key is configured.
   * @throws IllegalArgumentException if the configured site is invalid.
   */
  @Nullable
  public static DirectIntakeTransport create(final Settings settings) {
    final String apiKey = settings.apiKey();
    if (apiKey == null) {
      return null;
    }
    return new DirectIntakeTransport(
        URI.create("https://" + SiteHost.of("event-platform-intake", settings.site()) + "/api/v2/"),
        apiKey,
        HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).followRedirects(NEVER).build(),
        TimeUnit.MILLISECONDS::sleep);
  }

  @Override
  public void post(final String route, final byte[] json) throws IOException {
    final HttpRequest.Builder builder =
        HttpRequest.newBuilder(this.intake.resolve(route))
            .timeout(REQUEST_TIMEOUT)
            .header("Content-Type", "application/json")
            .header("dd-api-key", this.apiKey)
            .header("x-datadog-trace-id", this.traceId)
            .header("x-datadog-parent-id", this.traceId);
    Headers.addMetadata(builder);
    final HttpRequest request = builder.POST(ofByteArray(json)).build();
    final int status;
    try {
      status =
          Retries.send(
                  () ->
                      new HttpCall<>(
                          this.httpClient, request, HttpResponse.BodyHandlers.discarding()),
                  new IntakeRetryPolicy(),
                  MAX_RETRIES + 1,
                  this.retrySleeper,
                  () -> false)
              .statusCode();
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Request to " + route + " was interrupted", e);
    }
    if (status < 200 || status >= 300) {
      throw new IOException("Request to " + route + " returned error response " + status);
    }
  }

  private static final class IntakeRetryPolicy implements Retries.Policy {
    private boolean rateLimitRetry;

    @Override
    public boolean shouldRetry(final IOException error) {
      return error instanceof ConnectException || error instanceof HttpTimeoutException;
    }

    @Override
    public long retryDelayMillis(@Nullable final HttpResponse<?> response, final int attempt) {
      if (this.rateLimitRetry) {
        return -1;
      }
      if (response != null) {
        final int status = response.statusCode();
        if (status == 429) {
          final String reset = response.headers().firstValue("X-RateLimit-Reset").orElse(null);
          if (reset != null) {
            try {
              final long seconds = Long.parseLong(reset);
              if (seconds > 10) {
                return -1;
              }
              if (seconds >= 0) {
                this.rateLimitRetry = true;
                return TimeUnit.SECONDS.toMillis(seconds)
                    + ThreadLocalRandom.current().nextInt(401);
              }
            } catch (final NumberFormatException ignored) {
              // An invalid reset header falls back to exponential backoff.
            }
          }
        } else if (status < 500 || status > 599) {
          return -1;
        }
      }
      return INITIAL_RETRY_DELAY_MILLIS << (attempt - 1);
    }
  }
}

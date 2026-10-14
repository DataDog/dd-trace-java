package com.datadog.openfeature.internal.delivery;

import static java.net.http.HttpClient.Redirect.NEVER;
import static java.net.http.HttpRequest.BodyPublishers.ofByteArray;

import com.datadog.openfeature.internal.config.Settings;
import com.datadog.openfeature.internal.connector.EventTransport;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
                  this.httpClient,
                  request,
                  HttpResponse.BodyHandlers.discarding(),
                  MAX_RETRIES + 1,
                  attempt -> INITIAL_RETRY_DELAY_MILLIS << (attempt - 1),
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
}

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
  private final CdnConfigurationSource.RetrySleeper retrySleeper;

  DirectIntakeTransport(
      final URI intake,
      final String apiKey,
      final HttpClient httpClient,
      final CdnConfigurationSource.RetrySleeper retrySleeper) {
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
   */
  @Nullable
  public static DirectIntakeTransport create(final Settings settings) {
    final String apiKey = settings.apiKey();
    if (apiKey == null) {
      return null;
    }
    return new DirectIntakeTransport(
        URI.create("https://event-platform-intake." + settings.site() + "/api/v2/"),
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
    IOException failure = null;
    long delayMillis = INITIAL_RETRY_DELAY_MILLIS;
    for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
      try {
        if (attempt > 0) {
          this.retrySleeper.sleep(delayMillis);
          delayMillis *= 2;
        }
        final int status =
            this.httpClient.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        if (status >= 200 && status < 300) {
          return;
        }
        failure = new IOException("Request to " + route + " returned error response " + status);
        if (!isRetryableStatus(status)) {
          break;
        }
      } catch (final IOException e) {
        failure = e;
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IOException("Request to " + route + " was interrupted", e);
      }
    }
    throw failure;
  }

  static boolean isRetryableStatus(final int status) {
    return status == 429 || status >= 500;
  }
}

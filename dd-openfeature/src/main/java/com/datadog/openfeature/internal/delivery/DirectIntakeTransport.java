package com.datadog.openfeature.internal.delivery;

import static java.net.http.HttpClient.Redirect.NEVER;
import static java.net.http.HttpRequest.BodyPublishers.fromPublisher;
import static java.net.http.HttpRequest.BodyPublishers.ofByteArray;

import com.datadog.openfeature.internal.bridge.v1.EventTransport;
import com.datadog.openfeature.internal.config.SdkVersion;
import com.datadog.openfeature.internal.config.Settings;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Flow;
import java.util.concurrent.ThreadLocalRandom;
import javax.annotation.Nullable;

/**
 * Posts events directly to the Datadog event platform intake, authenticated with an API key. Each
 * batch is sent once, without retries or redirects, so an ambiguously accepted batch is never sent
 * twice.
 */
public final class DirectIntakeTransport implements EventTransport {
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

  /** Identifies the events like the agent event proxy does. */
  static final String ORIGIN = "dd-trace-java";

  private final URI intake;
  private final String apiKey;
  private final String traceId;
  private final HttpClient httpClient;

  DirectIntakeTransport(final URI intake, final String apiKey, final HttpClient httpClient) {
    this.intake = intake;
    this.apiKey = apiKey;
    // Intake requests are attributed to a stable, random trace id.
    this.traceId = Long.toUnsignedString(ThreadLocalRandom.current().nextLong() >>> 1);
    this.httpClient = httpClient;
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
        HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).followRedirects(NEVER).build());
  }

  @Override
  public void post(final String route, final byte[] json) throws IOException {
    post(route, ofByteArray(json));
  }

  @Override
  public void post(final String route, final List<ByteBuffer> json) throws IOException {
    long length = 0;
    for (final ByteBuffer chunk : json) {
      length += chunk.remaining();
    }
    post(route, fromPublisher(new ChunksPublisher(json), length));
  }

  private void post(final String route, final HttpRequest.BodyPublisher body) throws IOException {
    final HttpRequest.Builder builder =
        HttpRequest.newBuilder(this.intake.resolve(route))
            .timeout(REQUEST_TIMEOUT)
            .header("Content-Type", "application/json")
            .header("dd-api-key", this.apiKey)
            .header("x-datadog-trace-id", this.traceId)
            .header("x-datadog-parent-id", this.traceId)
            .header("DD-EVP-ORIGIN", ORIGIN)
            .header("DD-EVP-ORIGIN-VERSION", SdkVersion.VERSION);
    Headers.addMetadata(builder);
    final HttpRequest request = builder.POST(body).build();
    final int status;
    try {
      status =
          new HttpCall<>(this.httpClient, request, HttpResponse.BodyHandlers.discarding())
              .execute()
              .statusCode();
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Request to " + route + " was interrupted", e);
    }
    if (status < 200 || status >= 300) {
      throw new IOException("Request to " + route + " returned error response " + status);
    }
  }

  /** Publishes the chunks of a body in order, as read-only views, to each subscriber. */
  static final class ChunksPublisher implements Flow.Publisher<ByteBuffer> {
    private final List<ByteBuffer> chunks;

    ChunksPublisher(final List<ByteBuffer> chunks) {
      this.chunks = chunks;
    }

    @Override
    public void subscribe(final Flow.Subscriber<? super ByteBuffer> subscriber) {
      subscriber.onSubscribe(
          new Flow.Subscription() {
            private int next;
            private boolean done;

            @Override
            public synchronized void request(final long n) {
              if (done) {
                return;
              }
              if (n <= 0) {
                done = true;
                subscriber.onError(new IllegalArgumentException("Non-positive request: " + n));
                return;
              }
              for (long i = 0; i < n && next < chunks.size() && !done; i++) {
                subscriber.onNext(chunks.get(next++).asReadOnlyBuffer());
              }
              if (next == chunks.size() && !done) {
                done = true;
                subscriber.onComplete();
              }
            }

            @Override
            public synchronized void cancel() {
              done = true;
            }
          });
    }
  }
}

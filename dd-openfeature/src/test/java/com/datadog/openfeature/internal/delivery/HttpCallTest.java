package com.datadog.openfeature.internal.delivery;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import com.datadog.openfeature.internal.LocalHttpServer;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.tabletest.junit.TableTest;

@Timeout(15)
class HttpCallTest {
  @TableTest({
    "scenario | discard",
    "CDN      | false  ",
    "intake   | true   "
  })
  void timesOutStalledBodyAndClosesConnection(final boolean discard) throws Exception {
    try (StallingHttpServer server = new StallingHttpServer(true)) {
      final CountDownLatch handlerCalled = new CountDownLatch(1);
      final HttpResponse.BodyHandler<?> handler = bodyHandler(discard);
      final HttpCall<?> call =
          new HttpCall<>(
              HttpClient.newHttpClient(),
              request(server, Duration.ofSeconds(1)),
              info -> {
                handlerCalled.countDown();
                return handler.apply(info);
              });

      assertTrue(handlerCalled.await(5, SECONDS));
      assertThrows(HttpTimeoutException.class, call::execute);
      assertTrue(server.awaitDisconnect());
    }
  }

  @Test
  void timesOutStalledHeaders() throws Exception {
    try (StallingHttpServer server = new StallingHttpServer(false)) {
      final HttpCall<byte[]> call =
          new HttpCall<>(
              HttpClient.newHttpClient(),
              request(server, Duration.ofMillis(500)),
              HttpResponse.BodyHandlers.ofByteArray());

      server.awaitRequest();
      assertThrows(HttpTimeoutException.class, call::execute);
      server.sendHeaders();
      assertTrue(server.awaitDisconnect());
    }
  }

  @Test
  void cancellationBeforeHeadersCancelsLateSubscription() throws Exception {
    try (StallingHttpServer server = new StallingHttpServer(false)) {
      final HttpCall<byte[]> call =
          new HttpCall<>(
              HttpClient.newHttpClient(),
              request(server, Duration.ofSeconds(10)),
              HttpResponse.BodyHandlers.ofByteArray());

      server.awaitRequest();
      call.cancel();
      assertThrows(IOException.class, call::execute);
      server.sendHeaders();
      assertTrue(server.awaitDisconnect());
    }
  }

  @Test
  void cancellationDuringHandlerCreationCancelsLateSubscription() throws Exception {
    final CountDownLatch handlerCalled = new CountDownLatch(1);
    final CountDownLatch createSubscriber = new CountDownLatch(1);
    try (StallingHttpServer server = new StallingHttpServer(true)) {
      final HttpCall<byte[]> call =
          new HttpCall<>(
              HttpClient.newHttpClient(),
              request(server, Duration.ofSeconds(10)),
              info -> {
                handlerCalled.countDown();
                try {
                  if (!createSubscriber.await(5, SECONDS)) {
                    throw new IllegalStateException("Subscriber creation was not released");
                  }
                } catch (final InterruptedException e) {
                  Thread.currentThread().interrupt();
                  throw new IllegalStateException(e);
                }
                return HttpResponse.BodySubscribers.ofByteArray();
              });

      assertTrue(handlerCalled.await(5, SECONDS));
      call.cancel();
      createSubscriber.countDown();
      assertThrows(IOException.class, call::execute);
      assertTrue(server.awaitDisconnect());
    } finally {
      createSubscriber.countDown();
    }
  }

  @Test
  void interruptionClosesConnection() throws Exception {
    try (StallingHttpServer server = new StallingHttpServer(true)) {
      final HttpCall<byte[]> call =
          new HttpCall<>(
              HttpClient.newHttpClient(),
              request(server, Duration.ofSeconds(10)),
              HttpResponse.BodyHandlers.ofByteArray());

      server.awaitRequest();
      Thread.currentThread().interrupt();
      try {
        assertThrows(InterruptedException.class, call::execute);
      } finally {
        Thread.interrupted();
      }
      assertTrue(server.awaitDisconnect());
    }
  }

  @Test
  void preservesIoExceptionType() {
    final HttpClient client = mock(HttpClient.class);
    final ConnectException failure = new ConnectException("connection refused");
    doReturn(CompletableFuture.failedFuture(failure)).when(client).sendAsync(any(), any());
    final HttpCall<byte[]> call =
        new HttpCall<>(
            client,
            HttpRequest.newBuilder(URI.create("http://localhost/"))
                .timeout(Duration.ofSeconds(1))
                .build(),
            HttpResponse.BodyHandlers.ofByteArray());

    assertSame(failure, assertThrows(ConnectException.class, call::execute));
  }

  @Test
  void normalizesWrappedCancellation() {
    final HttpClient client = mock(HttpClient.class);
    final CancellationException cancelled = new CancellationException("Request cancelled");
    doReturn(CompletableFuture.failedFuture(new CompletionException(cancelled)))
        .when(client)
        .sendAsync(any(), any());
    final HttpCall<byte[]> call =
        new HttpCall<>(
            client,
            HttpRequest.newBuilder(URI.create("http://localhost/"))
                .timeout(Duration.ofSeconds(1))
                .build(),
            HttpResponse.BodyHandlers.ofByteArray());

    assertSame(cancelled, assertThrows(IOException.class, call::execute).getCause());
  }

  @Test
  void successfulResponsesRemainUsableAfterCancel() throws Exception {
    try (LocalHttpServer server = new LocalHttpServer()) {
      server.enqueue(200, "first").enqueue(200, "second");
      final HttpClient client = HttpClient.newHttpClient();
      final HttpRequest request =
          HttpRequest.newBuilder(server.uri("/")).timeout(Duration.ofSeconds(5)).build();
      final HttpCall<byte[]> first =
          new HttpCall<>(client, request, HttpResponse.BodyHandlers.ofByteArray());

      final byte[] body = first.execute().body();
      first.cancel();

      assertArrayEquals("first".getBytes(UTF_8), body);
      assertArrayEquals(
          "second".getBytes(UTF_8),
          new HttpCall<>(client, request, HttpResponse.BodyHandlers.ofByteArray())
              .execute()
              .body());
    }
  }

  private static HttpRequest request(final StallingHttpServer server, final Duration timeout) {
    return HttpRequest.newBuilder(server.uri()).timeout(timeout).build();
  }

  private static HttpResponse.BodyHandler<?> bodyHandler(final boolean discard) {
    return discard
        ? HttpResponse.BodyHandlers.discarding()
        : HttpResponse.BodyHandlers.ofByteArray();
  }
}

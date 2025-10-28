package com.datadog.debugger.uploader;

import static com.datadog.debugger.uploader.BatchUploader.APPLICATION_JSON;
import static com.datadog.debugger.uploader.BatchUploader.HEADER_DD_API_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import datadog.trace.api.Config;
import datadog.trace.relocate.api.RatelimitedLogger;
import java.io.IOException;
import java.net.ConnectException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import okhttp3.ConnectionSpec;
import okhttp3.HttpUrl;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for the snapshot uploader. */
@ExtendWith(MockitoExtension.class)
public class BatchUploaderTest {
  private static final MockResponse RESPONSE_200 = new MockResponse().setResponseCode(200);
  private static final String URL_PATH = "/lalala";
  public static final byte[] SNAPSHOT_BUFFER = "jsonBuffer".getBytes(StandardCharsets.UTF_8);
  private final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
  private final Duration REQUEST_IO_OPERATION_TIMEOUT = Duration.ofSeconds(5);
  private final Duration FOREVER_REQUEST_TIMEOUT = Duration.ofSeconds(1000);
  private static final String API_KEY_VALUE = "testkey";

  @Mock private Config config;
  @Mock private RatelimitedLogger ratelimitedLogger;

  private final MockWebServer server = new MockWebServer();
  private HttpUrl url;
  private final BatchUploader.RetryPolicy retryPolicy = new BatchUploader.RetryPolicy(3);

  private BatchUploader uploader;

  @BeforeEach
  public void setup() throws IOException {
    server.start();
    url = server.url(URL_PATH);

    when(config.getDynamicInstrumentationUploadTimeout())
        .thenReturn((int) REQUEST_TIMEOUT.getSeconds());

    uploader = new BatchUploader("test", config, url.toString(), ratelimitedLogger, retryPolicy);
  }

  @AfterEach
  public void tearDown() throws IOException {
    uploader.shutdown();
    try {
      server.shutdown();
    } catch (final IOException e) {
      // Looks like this happens for some unclear reason, but should not affect tests
    }
  }

  @Test
  void testUnixDomainSocket() {
    when(config.getAgentUnixDomainSocket()).thenReturn("/tmp/ddagent/agent.sock");
    uploader = new BatchUploader("test", config, "http://localhost:8126", retryPolicy);
    assertEquals(
        "datadog.common.socket.UnixDomainSocketFactory",
        uploader.getClient().socketFactory().getClass().getTypeName());
  }

  @Test
  void testOkHttpClientForcesCleartextConnspecWhenNotUsingTLS() {
    uploader = new BatchUploader("test", config, "http://example.com", retryPolicy);

    final List<ConnectionSpec> connectionSpecs = uploader.getClient().connectionSpecs();
    assertEquals(connectionSpecs.size(), 1);
    assertTrue(connectionSpecs.contains(ConnectionSpec.CLEARTEXT));
  }

  @Test
  void testOkHttpClientUsesDefaultConnspecsOverTLS() {
    uploader = new BatchUploader("test", config, "https://example.com", retryPolicy);

    final List<ConnectionSpec> connectionSpecs = uploader.getClient().connectionSpecs();
    assertEquals(connectionSpecs.size(), 2);
    assertTrue(connectionSpecs.contains(ConnectionSpec.MODERN_TLS));
    assertTrue(connectionSpecs.contains(ConnectionSpec.CLEARTEXT));
  }

  @Test
  public void test500Response() throws InterruptedException {
    server.enqueue(new MockResponse().setResponseCode(500));

    uploader.upload(SNAPSHOT_BUFFER);

    assertNotNull(server.takeRequest(5, TimeUnit.SECONDS));
  }

  @Test
  public void testConnectionRefused() throws IOException, InterruptedException {
    server.shutdown();

    uploader.upload(SNAPSHOT_BUFFER);

    // Shutting down uploader ensures all callbacks are called on http client
    uploader.shutdown();
    verify(ratelimitedLogger, atLeastOnce())
        .warn(
            eq("Failed to upload batch to {}"),
            ArgumentMatchers.argThat(arg -> arg.toString().startsWith(url.toString())),
            any(ConnectException.class));
  }

  @Test
  public void testTimeout() throws IOException, InterruptedException {
    server.enqueue(
        new MockResponse()
            .setHeadersDelay(
                REQUEST_IO_OPERATION_TIMEOUT.plus(Duration.ofMillis(1000)).toMillis(),
                TimeUnit.MILLISECONDS));

    uploader.upload(SNAPSHOT_BUFFER);

    assertNotNull(server.takeRequest(5, TimeUnit.SECONDS));
  }

  @Test
  public void testEnqueuedRequestsExecuted() throws IOException, InterruptedException {
    // We have to block all parallel requests to make sure queue is kept full
    for (int i = 0; i < BatchUploader.MAX_RUNNING_REQUESTS; i++) {
      server.enqueue(
          new MockResponse()
              .setHeadersDelay(
                  // 1 second should be enough to schedule all requests and not hit timeout
                  Duration.ofMillis(1000).toMillis(), TimeUnit.MILLISECONDS)
              .setResponseCode(200));
    }
    server.enqueue(RESPONSE_200);

    for (int i = 0; i < BatchUploader.MAX_RUNNING_REQUESTS; i++) {
      uploader.upload(SNAPSHOT_BUFFER);
    }

    uploader.upload(SNAPSHOT_BUFFER);

    // Make sure all expected requests happened
    for (int i = 0; i < BatchUploader.MAX_RUNNING_REQUESTS; i++) {
      assertNotNull(server.takeRequest(5, TimeUnit.SECONDS));
    }

    assertNotNull(server.takeRequest(2000, TimeUnit.MILLISECONDS), "Got enqueued request");
  }

  @Test
  public void testTooManyRequests() throws IOException, InterruptedException {
    // We need to make sure that initial requests that fill up the queue hang to the duration of the
    // test. So we specify insanely large timeout here.
    when(config.getDynamicInstrumentationUploadTimeout())
        .thenReturn((int) FOREVER_REQUEST_TIMEOUT.getSeconds());
    uploader = new BatchUploader("test", config, url.toString(), retryPolicy);

    // We have to block all parallel requests to make sure queue is kept full
    for (int i = 0; i < BatchUploader.MAX_RUNNING_REQUESTS; i++) {
      server.enqueue(
          new MockResponse()
              .setHeadersDelay(FOREVER_REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
              .setResponseCode(200));
    }
    server.enqueue(new MockResponse().setResponseCode(200));

    for (int i = 0; i < BatchUploader.MAX_RUNNING_REQUESTS; i++) {
      uploader.upload(SNAPSHOT_BUFFER);
    }

    // We schedule one additional request to check case when request would be rejected immediately
    // rather than added to the queue.
    for (int i = 0; i < BatchUploader.MAX_ENQUEUED_REQUESTS + 1; i++) {
      uploader.upload(SNAPSHOT_BUFFER);
    }
    // Make sure all expected requests happened
    for (int i = 0; i < BatchUploader.MAX_RUNNING_REQUESTS; i++) {
      assertNotNull(server.takeRequest(5, TimeUnit.SECONDS));
    }
    // Recordings after RecordingUploader.MAX_RUNNING_REQUESTS will not be executed because number
    // or parallel requests has been reached.
    assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS), "No more requests");
  }

  @Test
  public void testShutdown() throws IOException, InterruptedException {
    uploader.shutdown();

    uploader.upload(SNAPSHOT_BUFFER);

    assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS), "No more requests");
  }

  @Test
  public void testEmptyUrl() {
    Assertions.assertThrows(
        IllegalArgumentException.class, () -> new BatchUploader("test", config, "", retryPolicy));
  }

  @Test
  public void testNoContainerId() throws InterruptedException {
    // we don't explicitly specify a container ID
    server.enqueue(RESPONSE_200);
    BatchUploader uploaderWithNoContainerId =
        new BatchUploader(
            "test", config, url.toString(), ratelimitedLogger, retryPolicy, null, null);

    uploaderWithNoContainerId.upload(SNAPSHOT_BUFFER);
    uploaderWithNoContainerId.shutdown();

    RecordedRequest request = server.takeRequest(100, TimeUnit.MILLISECONDS);
    assertNull(request.getHeader("Datadog-Container-ID"));
  }

  @Test
  public void testContainerIdHeader() throws InterruptedException {
    server.enqueue(RESPONSE_200);

    BatchUploader uploaderWithContainerId =
        new BatchUploader(
            "test",
            config,
            url.toString(),
            ratelimitedLogger,
            retryPolicy,
            "testContainerId",
            "testEntityId");
    uploaderWithContainerId.upload(SNAPSHOT_BUFFER);
    uploaderWithContainerId.shutdown();

    RecordedRequest request = server.takeRequest(100, TimeUnit.MILLISECONDS);
    assertEquals("testContainerId", request.getHeader("Datadog-Container-ID"));
    assertEquals("testEntityId", request.getHeader("Datadog-Entity-ID"));
  }

  @Test
  public void testApiKey() throws InterruptedException {
    server.enqueue(RESPONSE_200);
    when(config.getApiKey()).thenReturn(API_KEY_VALUE);

    BatchUploader uploaderWithApiKey =
        new BatchUploader("test", config, url.toString(), ratelimitedLogger, retryPolicy);
    uploaderWithApiKey.upload(SNAPSHOT_BUFFER);
    uploaderWithApiKey.shutdown();

    RecordedRequest request = server.takeRequest(100, TimeUnit.MILLISECONDS);
    assertEquals(API_KEY_VALUE, request.getHeader(HEADER_DD_API_KEY));
  }

  @Test
  public void testUpload() throws InterruptedException {
    server.enqueue(RESPONSE_200);
    uploader.upload(SNAPSHOT_BUFFER);
    RecordedRequest recordedRequest = server.takeRequest(5, TimeUnit.SECONDS);
    assertNotNull(recordedRequest);
    String strBody = recordedRequest.getBody().readUtf8();
    assertEquals(new String(SNAPSHOT_BUFFER), strBody);
  }

  @Test
  public void testUploadMultiPart() throws InterruptedException {
    server.enqueue(RESPONSE_200);
    uploader.uploadAsMultipart(
        "",
        new BatchUploader.MultiPartContent(SNAPSHOT_BUFFER, "file", "file.json", APPLICATION_JSON));
    RecordedRequest recordedRequest = server.takeRequest(5, TimeUnit.SECONDS);
    assertNotNull(recordedRequest);
    String strBody = recordedRequest.getBody().readUtf8();
    assertTrue(strBody.contains(new String(SNAPSHOT_BUFFER)));
  }

  @Test
  public void testRetryOnFailure() throws IOException, InterruptedException {
    int port = server.getPort();
    server.shutdown();
    ServerSocket serverSocket = new ServerSocket(port);
    Thread t =
        new Thread(
            () -> {
              try {
                System.out.println("Accepting connection on port " + port);
                Socket socket = serverSocket.accept();
                System.out.println("Accepted connection, closing");
                socket.setSoLinger(true, 0);
                socket.close();
                serverSocket.close();
              } catch (Exception e) {
                e.printStackTrace();
              }
            });
    t.start();
    // only upload once. will fail on first attempt, will succeed on retry
    uploader.upload(SNAPSHOT_BUFFER);
    t.join();
    MockWebServer newServer = new MockWebServer();
    newServer.start(port);
    newServer.enqueue(RESPONSE_200);
    RecordedRequest recordedRequest = newServer.takeRequest(5, TimeUnit.SECONDS);
    assertNotNull(recordedRequest);
  }

  @Test
  public void testRetryOn500() throws InterruptedException {
    doTestRetryOnResponseCode(500, 2);
    doTestRetryOnResponseCode(408, 4);
    doTestRetryOnResponseCode(429, 6);
  }

  private void doTestRetryOnResponseCode(int code, int expectedReqCount)
      throws InterruptedException {
    server.enqueue(new MockResponse().setResponseCode(code));
    server.enqueue(RESPONSE_200);
    uploader.upload(SNAPSHOT_BUFFER);
    assertNotNull(server.takeRequest(5, TimeUnit.SECONDS));
    assertNotNull(server.takeRequest(5, TimeUnit.SECONDS));
    assertEquals(expectedReqCount, server.getRequestCount());
    assertEmptyFailures();
  }

  private void assertEmptyFailures() throws InterruptedException {
    int count = 0;
    while (uploader.getRetryPolicy().failures.size() > 0 && count < 300) {
      Thread.sleep(10);
      count++;
    }
    assertEquals(0, uploader.getRetryPolicy().failures.size());
  }

  @Test
  public void testMaxRetryOn500() throws InterruptedException {
    server.enqueue(new MockResponse().setResponseCode(500)); // first attempt
    server.enqueue(new MockResponse().setResponseCode(500)); // first retry
    server.enqueue(new MockResponse().setResponseCode(500));
    server.enqueue(new MockResponse().setResponseCode(500)); // last retry
    uploader.upload(SNAPSHOT_BUFFER);
    assertNotNull(server.takeRequest(5, TimeUnit.SECONDS));
    assertNotNull(server.takeRequest(5, TimeUnit.SECONDS));
    assertNotNull(server.takeRequest(5, TimeUnit.SECONDS));
    assertNotNull(server.takeRequest(5, TimeUnit.SECONDS));
    assertEmptyFailures();
    assertEquals(4, server.getRequestCount()); // first + 3 retries
  }
}

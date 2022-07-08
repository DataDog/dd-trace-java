package com.datadog.debugger.uploader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import datadog.trace.api.Config;
import datadog.trace.relocate.api.RatelimitedLogger;
import java.io.IOException;
import java.net.ConnectException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
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
  private static final String URL_PATH = "/lalala";
  public static final byte[] SNAPSHOT_BUFFER = "jsonBuffer".getBytes(StandardCharsets.UTF_8);
  private final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
  private final Duration REQUEST_IO_OPERATION_TIMEOUT = Duration.ofSeconds(5);
  private final Duration FOREVER_REQUEST_TIMEOUT = Duration.ofSeconds(1000);

  @Mock private Config config;
  @Mock private RatelimitedLogger ratelimitedLogger;

  private final MockWebServer server = new MockWebServer();
  private HttpUrl url;

  private BatchUploader uploader;

  @BeforeEach
  public void setup() throws IOException {
    server.start();
    url = server.url(URL_PATH);

    when(config.getFinalDebuggerSnapshotUrl()).thenReturn(server.url(URL_PATH).toString());
    when(config.getDebuggerUploadTimeout()).thenReturn((int) REQUEST_TIMEOUT.getSeconds());

    uploader = new BatchUploader(config, ratelimitedLogger);
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
  void testOkHttpClientForcesCleartextConnspecWhenNotUsingTLS() {
    when(config.getFinalDebuggerSnapshotUrl()).thenReturn("http://example.com");

    uploader = new BatchUploader(config);

    final List<ConnectionSpec> connectionSpecs = uploader.getClient().connectionSpecs();
    assertEquals(connectionSpecs.size(), 1);
    assertTrue(connectionSpecs.contains(ConnectionSpec.CLEARTEXT));
  }

  @Test
  void testOkHttpClientUsesDefaultConnspecsOverTLS() {
    when(config.getFinalDebuggerSnapshotUrl()).thenReturn("https://example.com");

    uploader = new BatchUploader(config);

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
    verify(ratelimitedLogger)
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
    server.enqueue(new MockResponse().setResponseCode(200));

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
    when(config.getDebuggerUploadTimeout()).thenReturn((int) FOREVER_REQUEST_TIMEOUT.getSeconds());
    uploader = new BatchUploader(config);

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

    final List<ByteBuffer> hangingRequests = new ArrayList<>();
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
    when(config.getFinalDebuggerSnapshotUrl()).thenReturn("");
    Assertions.assertThrows(IllegalArgumentException.class, () -> new BatchUploader(config));
  }

  @Test
  public void testNoContainerId() throws InterruptedException {
    // we don't explicitly specify a container ID
    server.enqueue(new MockResponse().setResponseCode(200));
    BatchUploader uploaderWithNoContainerId = new BatchUploader(config, ratelimitedLogger, null);

    uploaderWithNoContainerId.upload(SNAPSHOT_BUFFER);
    uploaderWithNoContainerId.shutdown();

    RecordedRequest request = server.takeRequest(100, TimeUnit.MILLISECONDS);
    assertNull(request.getHeader("Datadog-Container-ID"));
  }

  @Test
  public void testContainerIdHeader() throws InterruptedException {
    server.enqueue(new MockResponse().setResponseCode(200));

    BatchUploader uploaderWithContainerId =
        new BatchUploader(config, ratelimitedLogger, "testContainerId");
    uploaderWithContainerId.upload(SNAPSHOT_BUFFER);
    uploaderWithContainerId.shutdown();

    RecordedRequest request = server.takeRequest(100, TimeUnit.MILLISECONDS);
    assertEquals("testContainerId", request.getHeader("Datadog-Container-ID"));
  }
}

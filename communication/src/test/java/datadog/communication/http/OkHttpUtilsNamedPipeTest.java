package datadog.communication.http;

import static org.junit.jupiter.api.Assertions.assertEquals;

import javax.net.SocketFactory;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * OkHttp sends a retry on the connection it already holds, and the health check before that reuse
 * can block forever on a named pipe. Each attempt must open a new connection. A TCP socket stands
 * in for the pipe, so these run on any OS.
 */
class OkHttpUtilsNamedPipeTest {
  private final MockWebServer server = new MockWebServer();

  @BeforeEach
  void startServer() throws Exception {
    server.start();
  }

  @AfterEach
  void stopServer() throws Exception {
    server.shutdown();
  }

  @Test
  void retryAfterRequestTimeoutUsesANewConnection() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(408));
    assertEachAttemptUsesANewConnection();
  }

  @Test
  void retryAfterServiceUnavailableWithRetryAfterZeroUsesANewConnection() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(503).setHeader("Retry-After", "0"));
    assertEachAttemptUsesANewConnection();
  }

  private void assertEachAttemptUsesANewConnection() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(200));
    OkHttpClient client =
        OkHttpUtils.buildHttpClient(true, null, "test-pipe", 1_000)
            .newBuilder()
            .socketFactory(SocketFactory.getDefault())
            .build();
    // a body makes OkHttp run its extensive health check before reusing a connection
    Request request =
        new Request.Builder()
            .url(server.url("/v0.4/traces"))
            .put(RequestBody.create(MediaType.get("application/msgpack"), new byte[] {(byte) 0x90}))
            .build();

    try (Response response = client.newCall(request).execute()) {
      assertEquals(200, response.code());
    }

    assertEquals(2, server.getRequestCount());
    for (int i = 0; i < 2; i++) {
      RecordedRequest recorded = server.takeRequest();
      assertEquals("close", recorded.getHeader("Connection"));
      assertEquals(0, recorded.getSequenceNumber());
    }
  }
}

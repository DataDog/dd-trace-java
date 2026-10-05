package datadog.communication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import datadog.communication.http.HttpRetryPolicy;
import java.io.IOException;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EvpProxyApiTest {

  private MockWebServer server;
  private OkHttpClient client;

  @BeforeEach
  void setUp() throws IOException {
    server = new MockWebServer();
    server.start();
    client = new OkHttpClient.Builder().build();
  }

  @AfterEach
  void tearDown() throws IOException {
    client.dispatcher().executorService().shutdownNow();
    client.connectionPool().evictAll();
    server.shutdown();
  }

  @Test
  void reportsHttpStatusForRejectedRequest() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(404).setBody("not found"));
    final EvpProxyApi api =
        new EvpProxyApi(
            "123",
            server.url("/evp_proxy/v4/"),
            "event-platform-intake",
            HttpRetryPolicy.Factory.NEVER_RETRY,
            client,
            false);

    final HttpResponseException exception =
        assertThrows(
            HttpResponseException.class,
            () ->
                api.post(
                    "exposures",
                    RequestBody.create(MediaType.parse("application/json"), "{}"),
                    stream -> null,
                    null,
                    false));

    assertEquals(404, exception.getStatusCode());
    final RecordedRequest request = server.takeRequest();
    assertEquals("/evp_proxy/v4/api/v2/exposures", request.getPath());
    assertEquals("event-platform-intake", request.getHeader("X-Datadog-EVP-Subdomain"));
  }
}

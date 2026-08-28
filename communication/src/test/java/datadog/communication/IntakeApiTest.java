package datadog.communication;

import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.communication.http.HttpRetryPolicy;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class IntakeApiTest {

  private static final MediaType JSON = MediaType.parse("application/json");

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
  void requestsGzipResponseCompressionWhenEnabled() throws Exception {
    assertEquals("gzip", postAndReadAcceptEncoding(true));
  }

  @Test
  void requestsIdentityResponseEncodingWhenCompressionIsDisabled() throws Exception {
    assertEquals("identity", postAndReadAcceptEncoding(false));
  }

  @Test
  void addsCustomHeadersWithoutReplacingIntakeHeaders() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));
    final IntakeApi api =
        new IntakeApi(
            server.url("/api/v2/"),
            "api-key",
            "123",
            HttpRetryPolicy.Factory.NEVER_RETRY,
            client,
            false);
    final Map<String, String> requestHeaders = new HashMap<>();
    requestHeaders.put("DD-EVP-ORIGIN", "dd-trace-java");
    requestHeaders.put("DD-EVP-ORIGIN-VERSION", "1.2.3");

    api.post(
        "flagevaluation",
        RequestBody.create(JSON, "{}"),
        responseBody -> null,
        null,
        false,
        requestHeaders);

    final RecordedRequest request = server.takeRequest();
    assertEquals("/api/v2/flagevaluation", request.getPath());
    assertEquals("api-key", request.getHeader("dd-api-key"));
    assertEquals("123", request.getHeader("x-datadog-trace-id"));
    assertEquals("123", request.getHeader("x-datadog-parent-id"));
    assertEquals("dd-trace-java", request.getHeader("DD-EVP-ORIGIN"));
    assertEquals("1.2.3", request.getHeader("DD-EVP-ORIGIN-VERSION"));
  }

  private String postAndReadAcceptEncoding(final boolean responseCompression) throws Exception {
    server.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));
    final IntakeApi api =
        new IntakeApi(
            server.url("/api/v2/"),
            "api-key",
            "123",
            HttpRetryPolicy.Factory.NEVER_RETRY,
            client,
            responseCompression);

    api.post("flagevaluation", RequestBody.create(JSON, "{}"), responseBody -> null, null, false);

    final RecordedRequest request = server.takeRequest();
    assertEquals("/api/v2/flagevaluation", request.getPath());
    return request.getHeader("Accept-Encoding");
  }
}

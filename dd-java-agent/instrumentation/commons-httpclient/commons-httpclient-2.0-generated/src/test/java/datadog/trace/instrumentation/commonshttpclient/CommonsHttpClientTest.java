package datadog.trace.instrumentation.commonshttpclient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.core.DDSpan;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.commons.httpclient.HostConfiguration;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.commons.httpclient.methods.HeadMethod;
import org.apache.commons.httpclient.methods.PostMethod;
import org.apache.commons.httpclient.methods.PutMethod;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Tests for commons-httpclient instrumentation.
 *
 * <p>Verifies that {@code HttpClient.executeMethod()} creates spans with correct operation name,
 * span kind, HTTP method, URL, and status code tags for various HTTP operations.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class CommonsHttpClientTest extends AbstractInstrumentationTest {

  private static final int SERVER_PORT = 19876;
  private static final String BASE_URL = "http://localhost:" + SERVER_PORT;

  private HttpServer server;
  private HttpClient client;

  /** Headers captured from the most recent request to /propagation endpoint. */
  private final Map<String, String> capturedHeaders = new ConcurrentHashMap<>();

  @BeforeAll
  void setupServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress(SERVER_PORT), 0);

    server.createContext(
        "/success",
        new HttpHandler() {
          @Override
          public void handle(HttpExchange exchange) throws IOException {
            byte[] response = "{\"status\":\"ok\"}".getBytes("UTF-8");
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            OutputStream os = exchange.getResponseBody();
            os.write(response);
            os.close();
          }
        });

    server.createContext(
        "/echo",
        new HttpHandler() {
          @Override
          public void handle(HttpExchange exchange) throws IOException {
            byte[] response = "{\"echo\":\"ok\"}".getBytes("UTF-8");
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            OutputStream os = exchange.getResponseBody();
            os.write(response);
            os.close();
          }
        });

    server.createContext(
        "/not-found",
        new HttpHandler() {
          @Override
          public void handle(HttpExchange exchange) throws IOException {
            byte[] response = "{\"error\":\"not found\"}".getBytes("UTF-8");
            exchange.sendResponseHeaders(404, response.length);
            OutputStream os = exchange.getResponseBody();
            os.write(response);
            os.close();
          }
        });

    server.createContext(
        "/error",
        new HttpHandler() {
          @Override
          public void handle(HttpExchange exchange) throws IOException {
            byte[] response = "{\"error\":\"internal server error\"}".getBytes("UTF-8");
            exchange.sendResponseHeaders(500, response.length);
            OutputStream os = exchange.getResponseBody();
            os.write(response);
            os.close();
          }
        });

    // Endpoint that captures all request headers for context propagation verification
    server.createContext(
        "/propagation",
        new HttpHandler() {
          @Override
          public void handle(HttpExchange exchange) throws IOException {
            capturedHeaders.clear();
            for (Map.Entry<String, List<String>> entry : exchange.getRequestHeaders().entrySet()) {
              // HttpServer normalizes header names to Title-Case; store lowercase for easy lookup
              capturedHeaders.put(entry.getKey().toLowerCase(), entry.getValue().get(0));
            }
            byte[] response = "ok".getBytes("UTF-8");
            exchange.sendResponseHeaders(200, response.length);
            OutputStream os = exchange.getResponseBody();
            os.write(response);
            os.close();
          }
        });

    server.setExecutor(null);
    server.start();

    client = new HttpClient();
  }

  @AfterAll
  void tearDownServer() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  void getRequestCreatesSpanWithCorrectTags() throws Exception {
    GetMethod get = new GetMethod(BASE_URL + "/success");
    try {
      int statusCode = client.executeMethod(get);
      assertEquals(200, statusCode);
    } finally {
      get.releaseConnection();
    }

    writer.waitForTraces(1);
    List<DDSpan> spans = flattenTraces();

    DDSpan span = findSpanByOperation(spans, "http.request");
    assertNotNull(span, "Expected http.request span");
    assertHttpClientSpan(span, "GET", "/success", 200, false);
  }

  @Test
  void postRequestCreatesSpanWithCorrectMethod() throws Exception {
    PostMethod post = new PostMethod(BASE_URL + "/echo");
    post.setRequestBody("{\"key\":\"value\"}");
    try {
      int statusCode = client.executeMethod(post);
      assertEquals(200, statusCode);
    } finally {
      post.releaseConnection();
    }

    writer.waitForTraces(1);
    List<DDSpan> spans = flattenTraces();

    DDSpan span = findSpanByOperation(spans, "http.request");
    assertNotNull(span, "Expected http.request span");
    assertHttpClientSpan(span, "POST", "/echo", 200, false);
  }

  @Test
  void putRequestCreatesSpanWithCorrectMethod() throws Exception {
    PutMethod put = new PutMethod(BASE_URL + "/echo");
    put.setRequestBody("{\"updated\":true}");
    try {
      int statusCode = client.executeMethod(put);
      assertEquals(200, statusCode);
    } finally {
      put.releaseConnection();
    }

    writer.waitForTraces(1);
    List<DDSpan> spans = flattenTraces();

    DDSpan span = findSpanByOperation(spans, "http.request");
    assertNotNull(span, "Expected http.request span");
    assertHttpClientSpan(span, "PUT", "/echo", 200, false);
  }

  @Test
  void headRequestCreatesSpan() throws Exception {
    HeadMethod head = new HeadMethod(BASE_URL + "/success");
    try {
      int statusCode = client.executeMethod(head);
      assertEquals(200, statusCode);
    } finally {
      head.releaseConnection();
    }

    writer.waitForTraces(1);
    List<DDSpan> spans = flattenTraces();

    DDSpan span = findSpanByOperation(spans, "http.request");
    assertNotNull(span, "Expected http.request span");
    assertHttpClientSpan(span, "HEAD", "/success", 200, false);
  }

  @Test
  void requestWith404SetsStatusCodeTag() throws Exception {
    GetMethod get = new GetMethod(BASE_URL + "/not-found");
    try {
      int statusCode = client.executeMethod(get);
      assertEquals(404, statusCode);
    } finally {
      get.releaseConnection();
    }

    writer.waitForTraces(1);
    List<DDSpan> spans = flattenTraces();

    DDSpan span = findSpanByOperation(spans, "http.request");
    assertNotNull(span, "Expected http.request span");
    // 4xx statuses are client errors and flagged as span errors by default
    assertHttpClientSpan(span, "GET", "/not-found", 404, true);
  }

  @Test
  void requestWith500SetsErrorStatus() throws Exception {
    GetMethod get = new GetMethod(BASE_URL + "/error");
    try {
      int statusCode = client.executeMethod(get);
      assertEquals(500, statusCode);
    } finally {
      get.releaseConnection();
    }

    writer.waitForTraces(1);
    List<DDSpan> spans = flattenTraces();

    DDSpan span = findSpanByOperation(spans, "http.request");
    assertNotNull(span, "Expected http.request span");
    // 5xx statuses are server errors and not flagged as client span errors by default
    assertHttpClientSpan(span, "GET", "/error", 500, false);
  }

  @Test
  void connectionExceptionSetsErrorTags() throws Exception {
    GetMethod get = new GetMethod("http://localhost:1/nonexistent");
    try {
      assertThrows(
          IOException.class,
          () -> {
            client.executeMethod(get);
          });
    } finally {
      get.releaseConnection();
    }

    writer.waitForTraces(1);
    List<DDSpan> spans = flattenTraces();

    DDSpan span = findSpanByOperation(spans, "http.request");
    assertNotNull(span, "Expected http.request span even on connection error");
    assertTrue(span.isError(), "Span should be marked as error on exception");
    assertNotNull(span.getTag("error.type"), "Expected error.type tag");
    assertNotNull(span.getTag("error.message"), "Expected error.message tag");
  }

  @Test
  void executeMethodWithHostConfigCreatesSpan() throws Exception {
    HostConfiguration hostConfig = new HostConfiguration();
    hostConfig.setHost("localhost", SERVER_PORT, "http");
    GetMethod get = new GetMethod("/success");
    try {
      int statusCode = client.executeMethod(hostConfig, get);
      assertEquals(200, statusCode);
    } finally {
      get.releaseConnection();
    }

    writer.waitForTraces(1);
    List<DDSpan> spans = flattenTraces();

    DDSpan span = findSpanByOperation(spans, "http.request");
    assertNotNull(span, "Expected http.request span with HostConfiguration overload");
    assertHttpClientSpan(span, "GET", "/success", 200, false);
  }

  @Test
  void multipleRequestsCreateSeparateSpans() throws Exception {
    GetMethod get1 = new GetMethod(BASE_URL + "/success");
    GetMethod get2 = new GetMethod(BASE_URL + "/success");
    try {
      client.executeMethod(get1);
    } finally {
      get1.releaseConnection();
    }
    try {
      client.executeMethod(get2);
    } finally {
      get2.releaseConnection();
    }

    writer.waitForTraces(2);
    List<DDSpan> spans = flattenTraces();

    long httpRequestCount =
        spans.stream().filter(s -> "http.request".equals(s.getOperationName().toString())).count();
    assertEquals(2, httpRequestCount, "Expected two separate http.request spans");
  }

  @Test
  void contextPropagationInjectsDatadogTraceHeaders() throws Exception {
    GetMethod get = new GetMethod(BASE_URL + "/propagation");
    try {
      int statusCode = client.executeMethod(get);
      assertEquals(200, statusCode);
    } finally {
      get.releaseConnection();
    }

    writer.waitForTraces(1);
    List<DDSpan> spans = flattenTraces();

    DDSpan span = findSpanByOperation(spans, "http.request");
    assertNotNull(span, "Expected http.request span");

    // Verify Datadog propagation headers were injected into the outgoing request
    assertNotNull(
        capturedHeaders.get("x-datadog-trace-id"),
        "Expected x-datadog-trace-id header to be injected");
    assertNotNull(
        capturedHeaders.get("x-datadog-parent-id"),
        "Expected x-datadog-parent-id header to be injected");
    assertNotNull(
        capturedHeaders.get("x-datadog-sampling-priority"),
        "Expected x-datadog-sampling-priority header to be injected");

    // Verify the injected trace ID matches the span's trace ID
    long expectedTraceId = span.getTraceId().toLong();
    assertEquals(
        String.valueOf(expectedTraceId),
        capturedHeaders.get("x-datadog-trace-id"),
        "Injected trace ID must match the span's trace ID");

    // Verify the injected parent ID matches the span's span ID
    long expectedSpanId = span.getSpanId();
    assertEquals(
        String.valueOf(expectedSpanId),
        capturedHeaders.get("x-datadog-parent-id"),
        "Injected parent ID must match the span's span ID");
  }

  @Test
  void contextPropagationInjectsW3CTraceparentHeader() throws Exception {
    GetMethod get = new GetMethod(BASE_URL + "/propagation");
    try {
      int statusCode = client.executeMethod(get);
      assertEquals(200, statusCode);
    } finally {
      get.releaseConnection();
    }

    writer.waitForTraces(1);
    List<DDSpan> spans = flattenTraces();

    DDSpan span = findSpanByOperation(spans, "http.request");
    assertNotNull(span, "Expected http.request span");

    // Default propagation style includes W3C traceparent
    String traceparent = capturedHeaders.get("traceparent");
    assertNotNull(traceparent, "Expected traceparent header to be injected");

    // traceparent format: {version}-{trace-id}-{parent-id}-{trace-flags}
    String[] parts = traceparent.split("-");
    assertEquals(4, parts.length, "traceparent must have 4 dash-separated parts");
    assertEquals("00", parts[0], "traceparent version must be 00");
    // trace-id is 32 hex chars, parent-id is 16 hex chars
    assertEquals(32, parts[1].length(), "traceparent trace-id must be 32 hex chars");
    assertEquals(16, parts[2].length(), "traceparent parent-id must be 16 hex chars");
  }

  @Test
  void contextPropagationWithPostRequest() throws Exception {
    PostMethod post = new PostMethod(BASE_URL + "/propagation");
    post.setRequestBody("{\"data\":\"test\"}");
    try {
      int statusCode = client.executeMethod(post);
      assertEquals(200, statusCode);
    } finally {
      post.releaseConnection();
    }

    writer.waitForTraces(1);
    List<DDSpan> spans = flattenTraces();

    DDSpan span = findSpanByOperation(spans, "http.request");
    assertNotNull(span, "Expected http.request span");
    assertEquals("POST", String.valueOf(span.getTag("http.method")));
    assertEquals(200, span.getTag("http.status_code"));

    // Verify trace headers are injected for POST requests too
    assertNotNull(
        capturedHeaders.get("x-datadog-trace-id"),
        "Expected x-datadog-trace-id header on POST request");
    assertNotNull(
        capturedHeaders.get("x-datadog-parent-id"),
        "Expected x-datadog-parent-id header on POST request");

    // Verify IDs match the span
    assertEquals(
        String.valueOf(span.getTraceId().toLong()),
        capturedHeaders.get("x-datadog-trace-id"),
        "POST request trace ID must match span");
    assertEquals(
        String.valueOf(span.getSpanId()),
        capturedHeaders.get("x-datadog-parent-id"),
        "POST request parent ID must match span");
  }

  @Test
  void peerServiceTagsSetFromRequestUrl() throws Exception {
    GetMethod get = new GetMethod(BASE_URL + "/success");
    try {
      int statusCode = client.executeMethod(get);
      assertEquals(200, statusCode);
    } finally {
      get.releaseConnection();
    }

    writer.waitForTraces(1);
    List<DDSpan> spans = flattenTraces();

    DDSpan span = findSpanByOperation(spans, "http.request");
    assertNotNull(span, "Expected http.request span");

    // peer.hostname is the input tag that PeerServiceCalculator uses to compute peer.service
    assertEquals(
        "localhost",
        String.valueOf(span.getTag("peer.hostname")),
        "peer.hostname must be set from the request URL host");

    // peer.port should be set when the URL has a non-default port
    assertEquals(
        SERVER_PORT, span.getTag("peer.port"), "peer.port must be set from the request URL port");
  }

  @Test
  void peerServiceTagsSetOnErrorResponse() throws Exception {
    GetMethod get = new GetMethod(BASE_URL + "/error");
    try {
      int statusCode = client.executeMethod(get);
      assertEquals(500, statusCode);
    } finally {
      get.releaseConnection();
    }

    writer.waitForTraces(1);
    List<DDSpan> spans = flattenTraces();

    DDSpan span = findSpanByOperation(spans, "http.request");
    assertNotNull(span, "Expected http.request span");

    // peer.hostname should still be set even when the server returns an error
    assertEquals(
        "localhost",
        String.valueOf(span.getTag("peer.hostname")),
        "peer.hostname must be set even on error responses");
    assertEquals(
        SERVER_PORT, span.getTag("peer.port"), "peer.port must be set even on error responses");
    assertEquals(500, span.getTag("http.status_code"));
  }

  @Test
  void peerServiceTagsSetWithHostConfiguration() throws Exception {
    // When using HostConfiguration with a full URL, peer.hostname is extracted from the URI
    HostConfiguration hostConfig = new HostConfiguration();
    hostConfig.setHost("localhost", SERVER_PORT, "http");
    GetMethod get = new GetMethod(BASE_URL + "/success");
    try {
      int statusCode = client.executeMethod(hostConfig, get);
      assertEquals(200, statusCode);
    } finally {
      get.releaseConnection();
    }

    writer.waitForTraces(1);
    List<DDSpan> spans = flattenTraces();

    DDSpan span = findSpanByOperation(spans, "http.request");
    assertNotNull(span, "Expected http.request span");

    // peer.hostname is extracted from the request URI host
    assertEquals(
        "localhost",
        String.valueOf(span.getTag("peer.hostname")),
        "peer.hostname must be set when using HostConfiguration with full URL");
    assertEquals(
        SERVER_PORT,
        span.getTag("peer.port"),
        "peer.port must be set when using HostConfiguration with full URL");
  }

  @Test
  void peerServiceTagsSetOnConnectionException() throws Exception {
    GetMethod get = new GetMethod("http://localhost:1/nonexistent");
    try {
      assertThrows(
          IOException.class,
          () -> {
            client.executeMethod(get);
          });
    } finally {
      get.releaseConnection();
    }

    writer.waitForTraces(1);
    List<DDSpan> spans = flattenTraces();

    DDSpan span = findSpanByOperation(spans, "http.request");
    assertNotNull(span, "Expected http.request span even on connection error");
    assertTrue(span.isError(), "Span should be marked as error");

    // peer.hostname should still be set from the URL even when connection fails
    assertEquals(
        "localhost",
        String.valueOf(span.getTag("peer.hostname")),
        "peer.hostname must be set even on connection failure");
    // Port 1 was used in the request URL
    assertEquals(1, span.getTag("peer.port"), "peer.port must be set from the request URL");
  }

  // -- helpers --

  /**
   * Validates the complete structure of an HTTP client span, ensuring all required attributes are
   * set correctly. This catches regressions in any span attribute rather than relying on individual
   * assertions scattered across test methods.
   *
   * @param span the span to validate
   * @param expectedMethod the expected HTTP method (GET, POST, etc.)
   * @param expectedPath the expected URL path (e.g. "/success")
   * @param expectedStatusCode the expected HTTP status code, or 0 if no status (e.g. connection
   *     error)
   * @param expectedError whether the span should be marked as an error
   */
  private void assertHttpClientSpan(
      DDSpan span,
      String expectedMethod,
      String expectedPath,
      int expectedStatusCode,
      boolean expectedError) {
    // Span metadata
    assertEquals(
        "http.request",
        String.valueOf(span.getOperationName()),
        "Operation name must be 'http.request'");
    assertEquals("http", String.valueOf(span.getType()), "Span type must be 'http'");
    assertEquals(
        expectedMethod + " " + expectedPath,
        String.valueOf(span.getResourceName()),
        "Resource must follow 'METHOD /path' format");
    assertNotNull(span.getServiceName(), "Service name must be set");
    assertFalse(span.getServiceName().isEmpty(), "Service name must not be empty");

    // Required tags
    assertEquals("client", String.valueOf(span.getTag("span.kind")), "span.kind must be 'client'");
    assertEquals(
        expectedMethod, String.valueOf(span.getTag("http.method")), "http.method must be set");
    assertEquals(
        "commons-http-client",
        String.valueOf(span.getTag("component")),
        "component must be 'commons-http-client'");

    // URL tag
    assertNotNull(span.getTag("http.url"), "http.url must be set");
    assertTrue(
        String.valueOf(span.getTag("http.url")).contains(expectedPath),
        "http.url must contain the request path");

    // Status code (only set when a response was received)
    if (expectedStatusCode > 0) {
      assertEquals(
          expectedStatusCode,
          span.getTag("http.status_code"),
          "http.status_code must match response");
    }

    // Error flag
    assertEquals(expectedError, span.isError(), "Span error flag must match expected value");
  }

  private List<DDSpan> flattenTraces() {
    List<DDSpan> result = new ArrayList<>();
    for (List<DDSpan> trace : writer) {
      result.addAll(trace);
    }
    return result;
  }

  private DDSpan findSpanByOperation(List<DDSpan> spans, String operationName) {
    for (DDSpan span : spans) {
      if (operationName.equals(span.getOperationName().toString())) {
        return span;
      }
    }
    return null;
  }
}

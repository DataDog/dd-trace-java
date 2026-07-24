package datadog.trace.instrumentation.sparkjava;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.agent.test.utils.PortUtils;
import datadog.trace.api.DDTraceId;
import datadog.trace.core.DDSpan;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import spark.Request;
import spark.Response;
import spark.Route;
import spark.Spark;

/**
 * Tests for the SparkJava 2.x HTTP server instrumentation.
 *
 * <p>SparkJava runs on an embedded Jetty server. The Jetty instrumentation creates the server span,
 * and the SparkJava {@link RoutesInstrumentation} enriches it with route information from the
 * {@code Routes.find()} method.
 *
 * <p>Acceptance criteria verified by these tests:
 *
 * <ul>
 *   <li>A server span is created for each HTTP request handled by a SparkJava route
 *   <li>The operation name is set to {@code spark.request}
 *   <li>The span type is {@code web} and span.kind is {@code server}
 *   <li>The component tag is set to {@code spark-java}
 *   <li>The resource name is enriched to {@code HTTP_METHOD route_pattern} (e.g., {@code GET
 *       /hello/:name})
 *   <li>The http.route tag contains the parameterized route pattern, not the concrete path
 *   <li>HTTP tags (method, URL, status code) are set correctly
 *   <li>Error routes (500) set the error flag on the span
 *   <li>Unmatched routes (404) retain Jetty defaults — no SparkJava enrichment fires
 *   <li>Context propagation via Datadog headers links server spans to parent traces
 * </ul>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class SparkJavaTest extends AbstractInstrumentationTest {

  private int actualPort;

  @BeforeAll
  void setupServer() {
    actualPort = PortUtils.randomOpenPort();
    Spark.port(actualPort);

    Spark.get(
        "/hello",
        new Route() {
          @Override
          public Object handle(Request request, Response response) {
            response.type("text/plain");
            return "Hello, World!";
          }
        });

    Spark.get(
        "/hello/:name",
        new Route() {
          @Override
          public Object handle(Request request, Response response) {
            String name = request.params(":name");
            response.type("text/plain");
            return "Hello, " + name + "!";
          }
        });

    Spark.post(
        "/users",
        new Route() {
          @Override
          public Object handle(Request request, Response response) {
            response.type("application/json");
            response.status(201);
            return "{\"created\": true}";
          }
        });

    Spark.put(
        "/users/:id",
        new Route() {
          @Override
          public Object handle(Request request, Response response) {
            String id = request.params(":id");
            response.type("application/json");
            return "{\"updated\": true, \"id\": \"" + id + "\"}";
          }
        });

    Spark.delete(
        "/users/:id",
        new Route() {
          @Override
          public Object handle(Request request, Response response) {
            String id = request.params(":id");
            response.type("application/json");
            return "{\"deleted\": true, \"id\": \"" + id + "\"}";
          }
        });

    Spark.get(
        "/error",
        new Route() {
          @Override
          public Object handle(Request request, Response response) {
            throw new RuntimeException("Intentional error for testing");
          }
        });

    Spark.get(
        "/files/*",
        new Route() {
          @Override
          public Object handle(Request request, Response response) {
            response.type("text/plain");
            return "file content for " + request.splat()[0];
          }
        });

    Spark.before(
        "/filtered/*",
        new spark.Filter() {
          @Override
          public void handle(Request request, Response response) {
            response.header("X-Filtered", "true");
          }
        });

    Spark.get(
        "/filtered/resource",
        new Route() {
          @Override
          public Object handle(Request request, Response response) {
            response.type("text/plain");
            return "filtered response";
          }
        });

    Spark.after(
        "/after-filtered/*",
        new spark.Filter() {
          @Override
          public void handle(Request request, Response response) {
            response.header("X-After-Filtered", "true");
          }
        });

    Spark.get(
        "/after-filtered/resource",
        new Route() {
          @Override
          public Object handle(Request request, Response response) {
            response.type("text/plain");
            return "after-filtered response";
          }
        });

    Spark.awaitInitialization();
  }

  @AfterAll
  void tearDownServer() throws InterruptedException {
    Spark.stop();
    Thread.sleep(500);
  }

  // ---------------------------------------------------------------
  // Route enrichment tests — verify SparkJava sets operation name,
  // component, resource name, and http.route on the Jetty server span
  // ---------------------------------------------------------------

  @Test
  void getRouteCreatesServerSpanWithCorrectTags() throws InterruptedException, TimeoutException {
    httpGet("/hello");

    DDSpan serverSpan = waitForServerSpan();
    assertServerSpan(serverSpan, "GET", "/hello", 200, false);
  }

  @Test
  void getRouteWithPathParamUsesParameterizedRoutePattern()
      throws InterruptedException, TimeoutException {
    httpGet("/hello/spark-user");

    DDSpan serverSpan = waitForServerSpan();
    // The route pattern should be /hello/:name (parameterized), not /hello/spark-user (actual path)
    assertServerSpan(serverSpan, "GET", "/hello/:name", 200, false);
  }

  @Test
  void postRouteCreatesServerSpanWithCorrectStatusCode()
      throws InterruptedException, TimeoutException {
    httpRequest("/users", "POST", "test-body");

    DDSpan serverSpan = waitForServerSpan();
    assertServerSpan(serverSpan, "POST", "/users", 201, false);
  }

  @Test
  void putRouteWithPathParamCreatesServerSpan() throws InterruptedException, TimeoutException {
    httpRequest("/users/42", "PUT", "update-body");

    DDSpan serverSpan = waitForServerSpan();
    assertServerSpan(serverSpan, "PUT", "/users/:id", 200, false);
  }

  @Test
  void deleteRouteWithPathParamCreatesServerSpan() throws InterruptedException, TimeoutException {
    httpRequest("/users/99", "DELETE", null);

    DDSpan serverSpan = waitForServerSpan();
    assertServerSpan(serverSpan, "DELETE", "/users/:id", 200, false);
  }

  @Test
  void wildcardRouteUsesWildcardPattern() throws InterruptedException, TimeoutException {
    httpGet("/files/documents/report.pdf");

    DDSpan serverSpan = waitForServerSpan();
    assertServerSpan(serverSpan, "GET", "/files/*", 200, false);
  }

  @Test
  void beforeFilterDoesNotInterfereWithRouteEnrichment()
      throws InterruptedException, TimeoutException {
    httpGet("/filtered/resource");

    DDSpan serverSpan = waitForServerSpan();
    assertServerSpan(serverSpan, "GET", "/filtered/resource", 200, false);
  }

  @Test
  void afterFilterDoesNotInterfereWithSpanData() throws InterruptedException, TimeoutException {
    httpGet("/after-filtered/resource");

    DDSpan serverSpan = waitForServerSpan();
    assertServerSpan(serverSpan, "GET", "/after-filtered/resource", 200, false);
  }

  // ---------------------------------------------------------------
  // Span structure tests — verify individual span attributes
  // ---------------------------------------------------------------

  @Test
  void serverSpanHasCorrectType() throws InterruptedException, TimeoutException {
    httpGet("/hello");

    DDSpan serverSpan = waitForServerSpan();
    assertEquals("web", serverSpan.getSpanType(), "HTTP server spans should have type 'web'");
    assertEquals(
        "server",
        String.valueOf(serverSpan.getTag("span.kind")),
        "Span kind should be 'server' for HTTP server spans");
  }

  @Test
  void serverSpanHasCorrectOperationName() throws InterruptedException, TimeoutException {
    httpGet("/hello");

    DDSpan serverSpan = waitForServerSpan();
    assertEquals(
        "spark.request",
        serverSpan.getOperationName().toString(),
        "Operation name should be 'spark.request' for SparkJava routes");
  }

  @Test
  void serverSpanIncludesHttpUrlTag() throws InterruptedException, TimeoutException {
    httpGet("/hello");

    DDSpan serverSpan = waitForServerSpan();
    String httpUrl = String.valueOf(serverSpan.getTag("http.url"));
    assertNotNull(httpUrl, "Expected http.url tag to be set");
    assertTrue(
        httpUrl.contains("/hello"),
        "http.url tag should contain the request path, got: " + httpUrl);
    assertTrue(httpUrl.startsWith("http"), "http.url tag should be a full URL, got: " + httpUrl);
  }

  // ---------------------------------------------------------------
  // Error handling tests
  // ---------------------------------------------------------------

  @Test
  void errorRouteCreatesServerSpanWithErrorFlag() throws InterruptedException, TimeoutException {
    httpGet("/error");

    DDSpan serverSpan = waitForServerSpan();
    assertServerSpan(serverSpan, "GET", "/error", 500, true);
    // SparkJava catches exceptions internally via its ExceptionMapper before they propagate
    // to Jetty. The error flag is set solely from the 500 status code by Jetty's
    // HttpServerDecorator.onResponse(). Because the exception never reaches the Jetty handler,
    // error.type/error.message/error.stack are not populated on the span.
    assertNull(
        serverSpan.getTag("error.type"),
        "error.type should not be set — SparkJava catches exceptions before Jetty sees them");
    assertNull(
        serverSpan.getTag("error.message"),
        "error.message should not be set — SparkJava catches exceptions before Jetty sees them");
    assertNull(
        serverSpan.getTag("error.stack"),
        "error.stack should not be set — SparkJava catches exceptions before Jetty sees them");
  }

  @Test
  void notFoundRouteCreates404Span() throws InterruptedException, TimeoutException {
    httpGet("/nonexistent");

    DDSpan serverSpan = waitForServerSpan();
    // For 404, Routes.find() returns null so SparkJava enrichment does not fire.
    // The span retains Jetty defaults — no http.route or spark-java component tag is expected.
    // We can't use assertServerSpan() here because it asserts SparkJava-specific enrichment
    // (operation name, component, http.route) that won't be present on an unmatched route.
    assertEquals("web", serverSpan.getSpanType(), "Span type should be 'web' even for 404");
    assertEquals(
        "server", String.valueOf(serverSpan.getTag("span.kind")), "span.kind should be 'server'");
    assertEquals(404, serverSpan.getTag("http.status_code"), "http.status_code should be 404");
    assertEquals("GET", String.valueOf(serverSpan.getTag("http.method")), "http.method tag");
    assertNotNull(serverSpan.getTag("http.url"), "http.url tag should be set even for 404");
    assertEquals(false, serverSpan.isError(), "404 should not be marked as an error");
  }

  // ---------------------------------------------------------------
  // Context propagation tests
  // ---------------------------------------------------------------

  @Test
  void contextPropagationLinksServerSpanToParentTrace()
      throws InterruptedException, TimeoutException {
    Map<String, String> headers = new HashMap<>();
    headers.put("x-datadog-trace-id", "123456789");
    headers.put("x-datadog-parent-id", "987654321");
    httpGetWithHeaders("/hello", headers);

    DDSpan serverSpan = waitForServerSpan();
    assertServerSpan(serverSpan, "GET", "/hello", 200, false);
    assertEquals(
        DDTraceId.from("123456789"),
        serverSpan.getTraceId(),
        "Server span should inherit the trace ID from the propagated Datadog headers");
    assertEquals(
        987654321L,
        serverSpan.getParentId(),
        "Server span's parent ID should match the x-datadog-parent-id header value");
  }

  @Test
  void contextPropagationPreservesSparkJavaRouteEnrichment()
      throws InterruptedException, TimeoutException {
    Map<String, String> headers = new HashMap<>();
    headers.put("x-datadog-trace-id", "111111111");
    headers.put("x-datadog-parent-id", "222222222");
    httpGetWithHeaders("/hello", headers);

    DDSpan serverSpan = waitForServerSpan();
    // Verify SparkJava route enrichment still works with propagated context
    assertServerSpan(serverSpan, "GET", "/hello", 200, false);
    // Verify context propagation
    assertEquals(
        DDTraceId.from("111111111"),
        serverSpan.getTraceId(),
        "Trace ID should be inherited from propagated headers");
    assertEquals(222222222L, serverSpan.getParentId());
  }

  @Test
  void contextPropagationWorksWithParameterizedRoutes()
      throws InterruptedException, TimeoutException {
    Map<String, String> headers = new HashMap<>();
    headers.put("x-datadog-trace-id", "333333333");
    headers.put("x-datadog-parent-id", "444444444");
    httpGetWithHeaders("/hello/sparkuser", headers);

    DDSpan serverSpan = waitForServerSpan();
    assertServerSpan(serverSpan, "GET", "/hello/:name", 200, false);
    assertEquals(
        DDTraceId.from("333333333"),
        serverSpan.getTraceId(),
        "Trace ID should be inherited from propagated headers");
    assertEquals(
        444444444L, serverSpan.getParentId(), "Parent ID should match propagated header value");
  }

  @Test
  void contextPropagationPreservesErrorStatusOnErrorRoutes()
      throws InterruptedException, TimeoutException {
    Map<String, String> headers = new HashMap<>();
    headers.put("x-datadog-trace-id", "555555555");
    headers.put("x-datadog-parent-id", "666666666");
    httpGetWithHeaders("/error", headers);

    DDSpan serverSpan = waitForServerSpan();
    assertServerSpan(serverSpan, "GET", "/error", 500, true);
    assertEquals(
        DDTraceId.from("555555555"),
        serverSpan.getTraceId(),
        "Trace ID should be inherited even for error routes");
    assertEquals(
        666666666L,
        serverSpan.getParentId(),
        "Parent ID should match propagated header even for error routes");
  }

  @Test
  void differentPropagatedContextsProduceDistinctTraces()
      throws InterruptedException, TimeoutException {
    Map<String, String> headers1 = new HashMap<>();
    headers1.put("x-datadog-trace-id", "100000001");
    headers1.put("x-datadog-parent-id", "200000001");
    httpGetWithHeaders("/hello", headers1);

    Map<String, String> headers2 = new HashMap<>();
    headers2.put("x-datadog-trace-id", "100000002");
    headers2.put("x-datadog-parent-id", "200000002");
    httpGetWithHeaders("/hello", headers2);

    writer.waitForTraces(2);
    List<DDSpan> allSpans = flattenTraces();

    // Find both server spans
    DDSpan firstServerSpan = null;
    DDSpan secondServerSpan = null;
    for (DDSpan span : allSpans) {
      if ("server".equals(String.valueOf(span.getTag("span.kind")))
          || "web".equals(span.getSpanType())) {
        if (DDTraceId.from("100000001").equals(span.getTraceId())) {
          firstServerSpan = span;
        } else if (DDTraceId.from("100000002").equals(span.getTraceId())) {
          secondServerSpan = span;
        }
      }
    }

    assertNotNull(firstServerSpan, "Expected server span for first request (trace 100000001)");
    assertNotNull(secondServerSpan, "Expected server span for second request (trace 100000002)");

    // Verify each span links to its own propagated context
    assertNotEquals(
        firstServerSpan.getTraceId(),
        secondServerSpan.getTraceId(),
        "Each request should have its own distinct trace ID from propagated context");
    assertEquals(200000001L, firstServerSpan.getParentId());
    assertEquals(200000002L, secondServerSpan.getParentId());

    // Both should still have correct route enrichment
    assertEquals("GET /hello", firstServerSpan.getResourceName().toString());
    assertEquals("GET /hello", secondServerSpan.getResourceName().toString());
  }

  // ---------------------------------------------------------------
  // Helper methods
  // ---------------------------------------------------------------

  /**
   * Waits for at least one trace to be written, then finds and returns the server span. This
   * combines the common pattern of waiting + flattening + finding into a single call, reducing
   * boilerplate in test methods.
   *
   * @return the server span (never null — fails assertion if not found)
   * @throws InterruptedException if the thread is interrupted while waiting
   * @throws TimeoutException if no trace is written within the timeout
   */
  private DDSpan waitForServerSpan() throws InterruptedException, TimeoutException {
    writer.waitForTraces(1);
    List<DDSpan> spans = flattenTraces();
    DDSpan serverSpan = findServerSpan(spans);
    assertNotNull(serverSpan, "Expected to find a server span in the collected traces");
    return serverSpan;
  }

  /**
   * Flattens all collected traces into a single list of spans for easier assertion.
   *
   * @return all spans from all collected traces
   */
  private List<DDSpan> flattenTraces() {
    List<DDSpan> result = new ArrayList<>();
    for (List<DDSpan> trace : writer) {
      result.addAll(trace);
    }
    return result;
  }

  /**
   * Finds the server span in the list of spans. The server span is identified by having {@code
   * span.kind=server} or by having a {@code web} span type.
   *
   * @param spans the list of spans to search
   * @return the server span, or {@code null} if not found
   */
  private DDSpan findServerSpan(List<DDSpan> spans) {
    for (DDSpan span : spans) {
      if ("server".equals(String.valueOf(span.getTag("span.kind")))
          || "web".equals(span.getSpanType())) {
        return span;
      }
    }
    return null;
  }

  /**
   * Validates the complete structure of a server span, covering both SparkJava enrichment and the
   * underlying Jetty server span baseline. This single-point-of-assertion prevents regressions when
   * new required tags are added.
   *
   * <p>SparkJava enrichment (set by {@link RoutesInstrumentation}):
   *
   * <ul>
   *   <li>operation name = {@code spark.request}
   *   <li>component = {@code spark-java}
   *   <li>resource name = {@code HTTP_METHOD route_pattern}
   *   <li>http.route = parameterized route pattern
   * </ul>
   *
   * <p>Jetty baseline (set by the Jetty server instrumentation):
   *
   * <ul>
   *   <li>span type = {@code web}
   *   <li>span.kind = {@code server}
   *   <li>http.method, http.status_code, http.url
   *   <li>error flag (from HTTP status code)
   * </ul>
   *
   * @param span the server span to validate
   * @param httpMethod the expected HTTP method (e.g., "GET", "POST")
   * @param route the expected route pattern (e.g., "/hello/:name")
   * @param statusCode the expected HTTP status code
   * @param isError whether the span should be marked as errored
   */
  private void assertServerSpan(
      DDSpan span, String httpMethod, String route, int statusCode, boolean isError) {
    assertNotNull(span, "Expected a server span for " + httpMethod + " " + route);

    // SparkJava enrichment assertions
    assertEquals(
        "spark.request",
        span.getOperationName().toString(),
        "Operation name should be 'spark.request'");
    assertEquals(
        "spark-java",
        String.valueOf(span.getTag("component")),
        "component tag should be 'spark-java'");
    assertEquals(
        httpMethod + " " + route,
        span.getResourceName().toString(),
        "Resource name should be HTTP_METHOD + route_pattern");
    assertEquals(
        route,
        String.valueOf(span.getTag("http.route")),
        "http.route should contain the route pattern, not the actual path");

    // Jetty baseline assertions
    assertEquals("web", span.getSpanType(), "Span type should be 'web'");
    assertEquals(
        "server", String.valueOf(span.getTag("span.kind")), "span.kind should be 'server'");
    assertEquals(httpMethod, String.valueOf(span.getTag("http.method")), "http.method tag");
    assertEquals(statusCode, span.getTag("http.status_code"), "http.status_code tag");
    assertNotNull(span.getTag("http.url"), "http.url tag should be set");
    assertEquals(isError, span.isError(), "error flag");
  }

  /**
   * Makes an HTTP GET request to the SparkJava server with custom headers. Used for context
   * propagation tests to inject Datadog trace headers (e.g., {@code x-datadog-trace-id}, {@code
   * x-datadog-parent-id}) that simulate an upstream service propagating its trace context.
   *
   * @param path the request path (e.g., {@code /hello})
   * @param headers map of header name to value to set on the request
   * @return the HTTP status code
   */
  private int httpGetWithHeaders(String path, Map<String, String> headers) {
    try {
      URL url = new URL("http://localhost:" + actualPort + path);
      HttpURLConnection conn = (HttpURLConnection) url.openConnection();
      conn.setRequestMethod("GET");
      conn.setConnectTimeout(5000);
      conn.setReadTimeout(5000);
      if (headers != null) {
        for (Map.Entry<String, String> entry : headers.entrySet()) {
          conn.setRequestProperty(entry.getKey(), entry.getValue());
        }
      }
      int status = conn.getResponseCode();
      drainResponse(conn);
      conn.disconnect();
      return status;
    } catch (Exception e) {
      throw new RuntimeException("HTTP GET failed for path " + path, e);
    }
  }

  /**
   * Makes an HTTP GET request to the SparkJava server.
   *
   * @param path the request path (e.g., {@code /hello})
   * @return the HTTP status code
   */
  private int httpGet(String path) {
    try {
      URL url = new URL("http://localhost:" + actualPort + path);
      HttpURLConnection conn = (HttpURLConnection) url.openConnection();
      conn.setRequestMethod("GET");
      conn.setConnectTimeout(5000);
      conn.setReadTimeout(5000);
      int status = conn.getResponseCode();
      drainResponse(conn);
      conn.disconnect();
      return status;
    } catch (Exception e) {
      throw new RuntimeException("HTTP GET failed for path " + path, e);
    }
  }

  /**
   * Makes an HTTP request with the specified method and optional body.
   *
   * @param path the request path
   * @param method the HTTP method (e.g., POST, PUT, DELETE)
   * @param body the request body, or {@code null} for no body
   * @return the HTTP status code
   */
  private int httpRequest(String path, String method, String body) {
    try {
      URL url = new URL("http://localhost:" + actualPort + path);
      HttpURLConnection conn = (HttpURLConnection) url.openConnection();
      conn.setRequestMethod(method);
      conn.setConnectTimeout(5000);
      conn.setReadTimeout(5000);

      if (body != null) {
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "text/plain");
        try (OutputStream os = conn.getOutputStream()) {
          os.write(body.getBytes("UTF-8"));
        }
      }

      int status = conn.getResponseCode();
      drainResponse(conn);
      conn.disconnect();
      return status;
    } catch (Exception e) {
      throw new RuntimeException("HTTP " + method + " failed for path " + path, e);
    }
  }

  /**
   * Drains the response body to ensure the server-side processing completes fully before the
   * connection is closed.
   *
   * @param conn the HTTP connection to drain
   */
  private void drainResponse(HttpURLConnection conn) {
    try {
      InputStream is =
          conn.getResponseCode() >= 400 ? conn.getErrorStream() : conn.getInputStream();
      if (is != null) {
        BufferedReader reader = new BufferedReader(new InputStreamReader(is));
        while (reader.readLine() != null) {
          // drain
        }
        reader.close();
      }
    } catch (Exception ignored) {
      // ignore drain errors
    }
  }
}

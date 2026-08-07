package datadog.trace.instrumentation.sparkjava;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.agent.test.utils.PortUtils;
import datadog.trace.core.DDSpan;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
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
 * Forked test for the SparkJava 2.x instrumentation, running in an isolated JVM. This validates
 * that the {@link RoutesInstrumentation} loads and enriches Jetty server spans correctly when the
 * agent starts from scratch — no leftover state from other test classes.
 *
 * <p>This test focuses on the core enrichment contract: when a request matches a SparkJava route,
 * the server span gets operation name {@code spark.request}, component {@code spark-java}, and the
 * resource name / http.route reflect the parameterized route pattern.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class SparkJavaForkedTest extends AbstractInstrumentationTest {

  private int actualPort;

  @BeforeAll
  void setupServer() {
    actualPort = PortUtils.randomOpenPort();
    Spark.port(actualPort);

    Spark.get(
        "/ping",
        new Route() {
          @Override
          public Object handle(Request request, Response response) {
            response.type("text/plain");
            return "pong";
          }
        });

    Spark.get(
        "/items/:id",
        new Route() {
          @Override
          public Object handle(Request request, Response response) {
            response.type("application/json");
            return "{\"id\": \"" + request.params(":id") + "\"}";
          }
        });

    Spark.get(
        "/fail",
        new Route() {
          @Override
          public Object handle(Request request, Response response) {
            throw new RuntimeException("Forked test error");
          }
        });

    Spark.awaitInitialization();
  }

  @AfterAll
  void tearDownServer() throws InterruptedException {
    Spark.stop();
    Thread.sleep(500);
  }

  @Test
  void simpleRouteEnrichesServerSpan() throws InterruptedException, TimeoutException {
    httpGet("/ping");

    DDSpan serverSpan = waitForServerSpan();
    assertServerSpan(serverSpan, "GET", "/ping", 200, false);
  }

  @Test
  void parameterizedRoutePatternInResourceName() throws InterruptedException, TimeoutException {
    httpGet("/items/42");

    DDSpan serverSpan = waitForServerSpan();
    assertServerSpan(serverSpan, "GET", "/items/:id", 200, false);
  }

  @Test
  void errorRouteProducesErrorSpan() throws InterruptedException, TimeoutException {
    httpGet("/fail");

    DDSpan serverSpan = waitForServerSpan();
    assertServerSpan(serverSpan, "GET", "/fail", 500, true);
  }

  // ---------------------------------------------------------------
  // Helper methods
  // ---------------------------------------------------------------

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
   * @param route the expected route pattern (e.g., "/items/:id")
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
   * Waits for at least one trace to be written and returns the server span.
   *
   * @return the server span (never null — fails assertion if not found)
   * @throws InterruptedException if the thread is interrupted while waiting
   * @throws TimeoutException if no trace is written within the timeout
   */
  private DDSpan waitForServerSpan() throws InterruptedException, TimeoutException {
    writer.waitForTraces(1);
    List<DDSpan> spans = new ArrayList<>();
    for (List<DDSpan> trace : writer) {
      spans.addAll(trace);
    }
    DDSpan serverSpan = null;
    for (DDSpan span : spans) {
      if ("server".equals(String.valueOf(span.getTag("span.kind")))
          || "web".equals(span.getSpanType())) {
        serverSpan = span;
        break;
      }
    }
    assertNotNull(serverSpan, "Expected to find a server span in the collected traces");
    return serverSpan;
  }

  /**
   * Makes an HTTP GET request to the SparkJava server.
   *
   * @param path the request path
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
      InputStream is =
          conn.getResponseCode() >= 400 ? conn.getErrorStream() : conn.getInputStream();
      if (is != null) {
        BufferedReader reader = new BufferedReader(new InputStreamReader(is));
        while (reader.readLine() != null) {
          // drain
        }
        reader.close();
      }
      conn.disconnect();
      return status;
    } catch (Exception e) {
      throw new RuntimeException("HTTP GET failed for path " + path, e);
    }
  }
}

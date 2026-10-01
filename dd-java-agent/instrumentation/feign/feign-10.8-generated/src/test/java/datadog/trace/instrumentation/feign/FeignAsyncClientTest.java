package datadog.trace.instrumentation.feign;

import static datadog.trace.agent.test.assertions.Matchers.is;
import static datadog.trace.agent.test.assertions.Matchers.isNonNull;
import static datadog.trace.agent.test.assertions.Matchers.matches;
import static datadog.trace.agent.test.assertions.Matchers.validates;
import static datadog.trace.agent.test.assertions.SpanMatcher.span;
import static datadog.trace.agent.test.assertions.TagsMatcher.defaultTags;
import static datadog.trace.agent.test.assertions.TagsMatcher.tag;
import static datadog.trace.agent.test.assertions.TraceMatcher.SORT_BY_START_TIME;
import static datadog.trace.agent.test.assertions.TraceMatcher.trace;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.core.DDSpan;
import datadog.trace.junit.utils.config.WithConfig;
import feign.AsyncClient;
import feign.Client;
import feign.Request;
import feign.Response;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@WithConfig(key = "trace.enabled", value = "true")
public class FeignAsyncClientTest extends AbstractInstrumentationTest {

  private HttpServer httpServer;
  private int port;

  @BeforeAll
  void setupServer() throws IOException {
    httpServer = HttpServer.create(new InetSocketAddress(0), 0);

    httpServer.createContext(
        "/api/users",
        exchange -> {
          byte[] body = "{\"id\":1,\"name\":\"Alice\"}".getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
          }
        });

    httpServer.createContext(
        "/api/error",
        exchange -> {
          byte[] body = "{\"error\":\"Internal Server Error\"}".getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(500, body.length);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
          }
        });

    httpServer.setExecutor(null);
    httpServer.start();
    port = httpServer.getAddress().getPort();
  }

  @AfterAll
  void tearDownServer() {
    if (httpServer != null) {
      httpServer.stop(0);
    }
  }

  @Test
  void asyncGetCreatesClientSpan()
      throws ExecutionException, TimeoutException, InterruptedException {
    AgentSpan parentSpan = startSpan("test", "parent");
    AgentScope parentScope = activateSpan(parentSpan);
    Response response;
    try {
      AsyncClient<Object> asyncClient = new AsyncClient.Pseudo<>(new Client.Default(null, null));
      Request request = buildRequest(Request.HttpMethod.GET, "/api/users", null);
      CompletableFuture<Response> future =
          asyncClient.execute(request, defaultOptions(), Optional.empty());
      response = future.get(10, TimeUnit.SECONDS);
    } finally {
      parentScope.close();
      parentSpan.finish();
    }

    assertEquals(200, response.status());
    response.close();

    // AsyncClient.Pseudo delegates to Client.Default, which is also instrumented,
    // resulting in two client spans: one from the async instrumentation and one from the sync
    assertTraces(
        trace(
            SORT_BY_START_TIME,
            span().root().operationName(Pattern.compile("parent")),
            span()
                .operationName(Pattern.compile("http\\.request"))
                .type("http")
                .error(false)
                .childOfPrevious()
                .resourceName(r -> r != null && r.toString().startsWith("GET"))
                .tags(
                    defaultTags(),
                    tag("span.kind", matches("client")),
                    tag("component", matches("feign")),
                    tag("http.method", matches("GET")),
                    tag(
                        "http.url",
                        validates(v -> v != null && v.toString().contains("/api/users"))),
                    tag("http.status_code", is(200)),
                    tag("peer.hostname", matches("localhost")),
                    tag("peer.port", is(port))),
            span()
                .operationName(Pattern.compile("http\\.request"))
                .type("http")
                .error(false)
                .childOfPrevious()
                .tags(
                    defaultTags(),
                    tag("span.kind", matches("client")),
                    tag("component", matches("feign")),
                    tag("http.method", matches("GET")),
                    tag(
                        "http.url",
                        validates(v -> v != null && v.toString().contains("/api/users"))),
                    tag("http.status_code", is(200)),
                    tag("peer.hostname", matches("localhost")),
                    tag("peer.port", is(port)))));
  }

  @Test
  void asyncPostCreatesClientSpanWithCorrectMethod()
      throws ExecutionException, TimeoutException, InterruptedException {
    AgentSpan parentSpan = startSpan("test", "parent");
    AgentScope parentScope = activateSpan(parentSpan);
    Response response;
    try {
      AsyncClient<Object> asyncClient = new AsyncClient.Pseudo<>(new Client.Default(null, null));
      byte[] body = "{\"name\":\"Bob\"}".getBytes(StandardCharsets.UTF_8);
      Request request = buildRequest(Request.HttpMethod.POST, "/api/users", body);
      CompletableFuture<Response> future =
          asyncClient.execute(request, defaultOptions(), Optional.empty());
      response = future.get(10, TimeUnit.SECONDS);
    } finally {
      parentScope.close();
      parentSpan.finish();
    }

    assertEquals(200, response.status());
    response.close();

    // AsyncClient.Pseudo delegates to Client.Default, which is also instrumented,
    // resulting in two client spans: one from the async instrumentation and one from the sync
    assertTraces(
        trace(
            SORT_BY_START_TIME,
            span().root().operationName(Pattern.compile("parent")),
            span()
                .operationName(Pattern.compile("http\\.request"))
                .type("http")
                .error(false)
                .childOfPrevious()
                .resourceName(r -> r != null && r.toString().startsWith("POST"))
                .tags(
                    defaultTags(),
                    tag("span.kind", matches("client")),
                    tag("component", matches("feign")),
                    tag("http.method", matches("POST")),
                    tag(
                        "http.url",
                        validates(v -> v != null && v.toString().contains("/api/users"))),
                    tag("http.status_code", is(200)),
                    tag("peer.hostname", matches("localhost")),
                    tag("peer.port", is(port))),
            span()
                .operationName(Pattern.compile("http\\.request"))
                .type("http")
                .error(false)
                .childOfPrevious()
                .tags(
                    defaultTags(),
                    tag("span.kind", matches("client")),
                    tag("component", matches("feign")),
                    tag("http.method", matches("POST")),
                    tag(
                        "http.url",
                        validates(v -> v != null && v.toString().contains("/api/users"))),
                    tag("http.status_code", is(200)),
                    tag("peer.hostname", matches("localhost")),
                    tag("peer.port", is(port)))));
  }

  @Test
  void asyncErrorResponseSetsStatusCodeAndErrorFlag()
      throws ExecutionException, TimeoutException, InterruptedException {
    AgentSpan parentSpan = startSpan("test", "parent");
    AgentScope parentScope = activateSpan(parentSpan);
    Response response;
    try {
      AsyncClient<Object> asyncClient = new AsyncClient.Pseudo<>(new Client.Default(null, null));
      Request request = buildRequest(Request.HttpMethod.GET, "/api/error", null);
      CompletableFuture<Response> future =
          asyncClient.execute(request, defaultOptions(), Optional.empty());
      response = future.get(10, TimeUnit.SECONDS);
    } finally {
      parentScope.close();
      parentSpan.finish();
    }

    assertEquals(500, response.status());
    response.close();

    // 500 is a server error, not in dd-trace-java's default HTTP client error statuses (400-499)
    // AsyncClient.Pseudo delegates to Client.Default, producing two client spans
    assertTraces(
        trace(
            SORT_BY_START_TIME,
            span().root().operationName(Pattern.compile("parent")),
            span()
                .operationName(Pattern.compile("http\\.request"))
                .type("http")
                .error(false)
                .childOfPrevious()
                .tags(
                    defaultTags(),
                    tag("span.kind", matches("client")),
                    tag("component", matches("feign")),
                    tag("http.method", matches("GET")),
                    tag(
                        "http.url",
                        validates(v -> v != null && v.toString().contains("/api/error"))),
                    tag("http.status_code", is(500)),
                    tag("peer.hostname", matches("localhost")),
                    tag("peer.port", is(port))),
            span()
                .operationName(Pattern.compile("http\\.request"))
                .type("http")
                .error(false)
                .childOfPrevious()
                .tags(
                    defaultTags(),
                    tag("span.kind", matches("client")),
                    tag("component", matches("feign")),
                    tag("http.method", matches("GET")),
                    tag(
                        "http.url",
                        validates(v -> v != null && v.toString().contains("/api/error"))),
                    tag("http.status_code", is(500)),
                    tag("peer.hostname", matches("localhost")),
                    tag("peer.port", is(port)))));
  }

  @Test
  void asyncConnectionExceptionSetsErrorTags() throws TimeoutException, InterruptedException {
    AgentSpan parentSpan = startSpan("test", "parent");
    AgentScope parentScope = activateSpan(parentSpan);
    try {
      AsyncClient<Object> asyncClient = new AsyncClient.Pseudo<>(new Client.Default(null, null));
      // Use a port that is not listening to force a connection error
      Request request =
          Request.create(
              Request.HttpMethod.GET,
              "http://localhost:1/not-listening",
              Collections.emptyMap(),
              null,
              StandardCharsets.UTF_8);
      CompletableFuture<Response> future =
          asyncClient.execute(request, defaultOptions(), Optional.empty());
      try {
        future.get(10, TimeUnit.SECONDS);
      } catch (ExecutionException expected) {
        // expected - connection refused wrapped in ExecutionException
      }
    } finally {
      parentScope.close();
      parentSpan.finish();
    }

    // AsyncClient.Pseudo delegates to Client.Default, producing two client spans (both with error)
    assertTraces(
        trace(
            SORT_BY_START_TIME,
            span().root().operationName(Pattern.compile("parent")),
            span()
                .operationName(Pattern.compile("http\\.request"))
                .type("http")
                .error(true)
                .childOfPrevious()
                .tags(
                    defaultTags(),
                    tag("span.kind", matches("client")),
                    tag("component", matches("feign")),
                    tag("http.method", matches("GET")),
                    tag(
                        "http.url",
                        validates(v -> v != null && v.toString().contains("/not-listening"))),
                    tag("error.type", isNonNull()),
                    tag("error.message", isNonNull()),
                    tag("error.stack", isNonNull()),
                    tag("peer.hostname", matches("localhost")),
                    tag("peer.port", is(1))),
            span()
                .operationName(Pattern.compile("http\\.request"))
                .type("http")
                .error(true)
                .childOfPrevious()
                .tags(
                    defaultTags(),
                    tag("span.kind", matches("client")),
                    tag("component", matches("feign")),
                    tag("http.method", matches("GET")),
                    tag(
                        "http.url",
                        validates(v -> v != null && v.toString().contains("/not-listening"))),
                    tag("error.type", isNonNull()),
                    tag("error.message", isNonNull()),
                    tag("error.stack", isNonNull()),
                    tag("peer.hostname", matches("localhost")),
                    tag("peer.port", is(1)))));
  }

  @Test
  void asyncContextPropagationInjectsHeaders()
      throws ExecutionException, TimeoutException, InterruptedException {
    // Capture headers received by the server
    Map<String, List<String>> receivedHeaders = new HashMap<>();
    httpServer.createContext(
        "/api/headers",
        exchange -> {
          for (Map.Entry<String, List<String>> entry : exchange.getRequestHeaders().entrySet()) {
            receivedHeaders.put(entry.getKey().toLowerCase(), entry.getValue());
          }
          exchange.sendResponseHeaders(200, -1);
          exchange.close();
        });

    AgentSpan parentSpan = startSpan("test", "parent");
    AgentScope parentScope = activateSpan(parentSpan);
    Response response;
    try {
      AsyncClient<Object> asyncClient = new AsyncClient.Pseudo<>(new Client.Default(null, null));
      Request request = buildRequest(Request.HttpMethod.GET, "/api/headers", null);
      CompletableFuture<Response> future =
          asyncClient.execute(request, defaultOptions(), Optional.empty());
      response = future.get(10, TimeUnit.SECONDS);
    } finally {
      parentScope.close();
      parentSpan.finish();
    }

    assertEquals(200, response.status());
    response.close();

    // AsyncClient.Pseudo delegates to Client.Default, producing two client spans
    assertTraces(
        trace(
            SORT_BY_START_TIME,
            span().root().operationName(Pattern.compile("parent")),
            span()
                .operationName(Pattern.compile("http\\.request"))
                .type("http")
                .error(false)
                .childOfPrevious()
                .tags(
                    defaultTags(),
                    tag("span.kind", matches("client")),
                    tag("component", matches("feign")),
                    tag("http.method", matches("GET")),
                    tag(
                        "http.url",
                        validates(v -> v != null && v.toString().contains("/api/headers"))),
                    tag("http.status_code", is(200)),
                    tag("peer.hostname", matches("localhost")),
                    tag("peer.port", is(port))),
            span()
                .operationName(Pattern.compile("http\\.request"))
                .type("http")
                .error(false)
                .childOfPrevious()
                .tags(
                    defaultTags(),
                    tag("span.kind", matches("client")),
                    tag("component", matches("feign")),
                    tag("http.method", matches("GET")),
                    tag(
                        "http.url",
                        validates(v -> v != null && v.toString().contains("/api/headers"))),
                    tag("http.status_code", is(200)),
                    tag("peer.hostname", matches("localhost")),
                    tag("peer.port", is(port)))));

    // Verify context propagation headers were injected
    assertTrue(
        receivedHeaders.containsKey("x-datadog-trace-id"),
        "Server should receive x-datadog-trace-id header. Received headers: "
            + receivedHeaders.keySet());
    assertTrue(
        receivedHeaders.containsKey("x-datadog-parent-id"),
        "Server should receive x-datadog-parent-id header");
    assertTrue(
        receivedHeaders.containsKey("x-datadog-sampling-priority"),
        "Server should receive x-datadog-sampling-priority header");

    String traceId = receivedHeaders.get("x-datadog-trace-id").get(0);
    String parentId = receivedHeaders.get("x-datadog-parent-id").get(0);
    assertNotNull(traceId, "x-datadog-trace-id should not be null");
    assertNotNull(parentId, "x-datadog-parent-id should not be null");
    assertFalse(traceId.isEmpty(), "x-datadog-trace-id should not be empty");
    assertFalse(parentId.isEmpty(), "x-datadog-parent-id should not be empty");

    // Verify the trace ID matches the active trace
    List<DDSpan> allSpans = flattenTraces();
    DDSpan clientSpan = findSpanByKind(allSpans, "client");
    assertNotNull(clientSpan, "Expected an HTTP client span from feign async");
    assertEquals(
        String.valueOf(clientSpan.getTraceId().toLong()),
        traceId,
        "Injected trace ID should match the client span's trace ID");
  }

  @Test
  void asyncSpanFinishesAfterFutureCompletes()
      throws ExecutionException, TimeoutException, InterruptedException {
    // Verify the span lifecycle: span should start at execute() and finish when the future
    // completes, capturing the response status code from the completed future
    AsyncClient<Object> asyncClient = new AsyncClient.Pseudo<>(new Client.Default(null, null));
    Request request = buildRequest(Request.HttpMethod.GET, "/api/users", null);
    CompletableFuture<Response> future =
        asyncClient.execute(request, defaultOptions(), Optional.empty());
    Response response = future.get(10, TimeUnit.SECONDS);
    response.close();

    // AsyncClient.Pseudo delegates to Client.Default, producing two client spans
    assertTraces(
        trace(
            SORT_BY_START_TIME,
            span()
                .operationName(Pattern.compile("http\\.request"))
                .type("http")
                .error(false)
                .root()
                .tags(
                    defaultTags(),
                    tag("span.kind", matches("client")),
                    tag("component", matches("feign")),
                    tag("http.method", matches("GET")),
                    tag(
                        "http.url",
                        validates(v -> v != null && v.toString().contains("/api/users"))),
                    tag("http.status_code", is(200)),
                    tag("peer.hostname", matches("localhost")),
                    tag("peer.port", is(port))),
            span()
                .operationName(Pattern.compile("http\\.request"))
                .type("http")
                .error(false)
                .childOfPrevious()
                .tags(
                    defaultTags(),
                    tag("span.kind", matches("client")),
                    tag("component", matches("feign")),
                    tag("http.method", matches("GET")),
                    tag(
                        "http.url",
                        validates(v -> v != null && v.toString().contains("/api/users"))),
                    tag("http.status_code", is(200)),
                    tag("peer.hostname", matches("localhost")),
                    tag("peer.port", is(port)))));
  }

  // ---- Helper methods ----

  private Request buildRequest(Request.HttpMethod method, String path, byte[] requestBody) {
    String url = "http://localhost:" + port + path;
    Map<String, Collection<String>> headers = new HashMap<>();
    headers.put("Accept", Collections.singletonList("application/json"));
    headers.put("Content-Type", Collections.singletonList("application/json"));
    return Request.create(method, url, headers, requestBody, StandardCharsets.UTF_8);
  }

  private Request.Options defaultOptions() {
    return new Request.Options(5, TimeUnit.SECONDS, 5, TimeUnit.SECONDS, true);
  }

  private List<DDSpan> flattenTraces() {
    List<DDSpan> result = new ArrayList<>();
    for (List<DDSpan> trace : writer) {
      result.addAll(trace);
    }
    return result;
  }

  private DDSpan findSpanByKind(List<DDSpan> spans, String spanKind) {
    for (DDSpan span : spans) {
      if (spanKind.equals(String.valueOf(span.getTag("span.kind")))) {
        return span;
      }
    }
    return null;
  }
}

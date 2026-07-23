package datadog.trace.instrumentation.quarkus_rest_client_reactive_javax;

import static datadog.trace.agent.test.assertions.SpanMatcher.span;
import static datadog.trace.agent.test.assertions.TraceMatcher.trace;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.core.Response;
import org.eclipse.microprofile.rest.client.RestClientBuilder;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class QuarkusRestClientJavaxInstrumentationTest extends AbstractInstrumentationTest {

  private static HttpServer server;
  private static int port;
  private static final AtomicReference<String> capturedTraceId = new AtomicReference<>();
  private static final AtomicReference<String> capturedParentId = new AtomicReference<>();

  @RegisterRestClient
  public interface HelloClient {
    @GET
    @Path("/hello")
    Response hello();
  }

  @BeforeAll
  static void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext(
        "/hello",
        new HttpHandler() {
          @Override
          public void handle(HttpExchange exchange) throws IOException {
            capturedTraceId.set(exchange.getRequestHeaders().getFirst("x-datadog-trace-id"));
            capturedParentId.set(exchange.getRequestHeaders().getFirst("x-datadog-parent-id"));
            byte[] body = "hello".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
          }
        });
    server.start();
    port = server.getAddress().getPort();
  }

  @AfterAll
  static void stopServer() {
    server.stop(0);
  }

  @Test
  void propagationHeadersAreInjectedOnRestClientCall() throws Exception {
    HelloClient client =
        RestClientBuilder.newBuilder()
            .baseUri(URI.create("http://localhost:" + port))
            .build(HelloClient.class);

    try (Response response = client.hello()) {
      assertEquals(200, response.getStatus());
    }

    assertNotNull(capturedTraceId.get(), "x-datadog-trace-id header should be injected");
    assertNotNull(capturedParentId.get(), "x-datadog-parent-id header should be injected");

    assertTraces(trace(span().type("http")));
  }

  @Test
  void spanIsCreatedForEachRequest() throws Exception {
    HelloClient client =
        RestClientBuilder.newBuilder()
            .baseUri(URI.create("http://localhost:" + port))
            .build(HelloClient.class);

    try (Response r1 = client.hello()) {
      assertEquals(200, r1.getStatus());
    }
    try (Response r2 = client.hello()) {
      assertEquals(200, r2.getStatus());
    }

    assertTraces(trace(span().type("http")), trace(span().type("http")));
  }
}

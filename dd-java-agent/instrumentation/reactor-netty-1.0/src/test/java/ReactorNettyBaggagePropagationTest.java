import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import datadog.context.Context;
import datadog.context.ContextScope;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.Baggage;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import reactor.netty.http.client.HttpClient;

/**
 * Regression test for the W3C baggage header not propagating on outgoing Reactor Netty requests.
 *
 * <p>The bug: the connect-span path carried only {@code activeSpan()} across the subscription ->
 * I/O thread hand-off, dropping the rest of the Datadog {@code Context} (including baggage). The
 * outgoing request then had no baggage to inject, so the {@code baggage} header was silently
 * skipped. This test sets a baggage item in the active context, makes one outgoing request, and
 * asserts the server received the {@code baggage} header.
 *
 * <p>It is a <em>coupling</em> test: producer (sets baggage) -> Reactor Netty carrier -> consumer
 * (injects the header). The failure it guards against lives in the hand-off between integrations,
 * not in any one of them, so per-integration tests would not catch it.
 */
class ReactorNettyBaggagePropagationTest extends AbstractInstrumentationTest {

  private static HttpServer mockServer;
  private static String baseUrl;
  private static final AtomicReference<String> capturedBaggage = new AtomicReference<>();

  @BeforeAll
  static void startServer() throws IOException {
    mockServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    mockServer.createContext(
        "/capture",
        exchange -> {
          capturedBaggage.set(exchange.getRequestHeaders().getFirst("baggage"));
          byte[] body = "ok".getBytes();
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    mockServer.setExecutor(Executors.newCachedThreadPool());
    mockServer.start();
    baseUrl =
        "http://"
            + mockServer.getAddress().getHostString()
            + ":"
            + mockServer.getAddress().getPort();
  }

  @AfterAll
  static void stopServer() {
    if (mockServer != null) {
      mockServer.stop(0);
      mockServer = null;
    }
  }

  @Test
  void baggageHeaderPropagatedOnOutgoingRequest() {
    capturedBaggage.set(null);
    Baggage baggage = Baggage.create(Collections.singletonMap("user.id", "abc123"));

    AgentSpan span = AgentTracer.startSpan("test", "parent");
    try (AgentScope spanScope = AgentTracer.activateSpan(span)) {
      // Active context now carries both the span and the baggage — the exact shape the connect-span
      // path must carry across the subscription -> I/O thread hand-off.
      try (ContextScope baggageScope = Context.current().with(baggage).attach()) {
        HttpClient.create()
            .get()
            .uri(baseUrl + "/capture")
            .response()
            .block(Duration.ofSeconds(10));
      }
    } finally {
      span.finish();
    }

    String header = capturedBaggage.get();
    assertNotNull(
        header,
        "outgoing request must carry a W3C 'baggage' header when baggage is in the active context;"
            + " null means the connect-span path dropped the context (carried only the span)");
    assertTrue(
        header.contains("user.id=abc123"),
        "baggage header should contain the propagated item, was: " + header);
  }
}

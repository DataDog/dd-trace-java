package test.camel;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import datadog.context.Context;
import datadog.context.ContextScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.core.DDSpan;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.impl.DefaultCamelContext;
import org.junit.jupiter.api.Test;

class CamelTransportTest extends AbstractCamelTest {
  @Test
  void httpTransportOwnsWirePropagationInsideNestedRoutes() throws Exception {
    AtomicReference<Headers> wireHeaders = new AtomicReference<>();
    AtomicReference<AgentSpan> restoredOuter = new AtomicReference<>();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/wire",
        exchange -> {
          wireHeaders.set(exchange.getRequestHeaders());
          byte[] response = "response".getBytes(UTF_8);
          exchange.getRequestBody().close();
          exchange.sendResponseHeaders(200, response.length);
          try (java.io.OutputStream output = exchange.getResponseBody()) {
            output.write(response);
          } finally {
            exchange.close();
          }
        });
    server.start();
    Context original = Context.current();
    AgentSpan parent = startSpan("test", "transport-parent");
    try (DefaultCamelContext context = new DefaultCamelContext()) {
      String uri = "http://127.0.0.1:" + server.getAddress().getPort() + "/wire?httpMethod=POST";
      context.addRoutes(
          new RouteBuilder() {
            @Override
            public void configure() {
              from("direct:outer")
                  .routeId("http-outer")
                  .to("direct:inner")
                  .process(exchange -> restoredOuter.set(activeSpan()));
              from("direct:inner").routeId("http-inner").to(uri).convertBodyTo(String.class);
            }
          });
      context.start();
      try (ProducerTemplate producer = context.createProducerTemplate();
          ContextScope ignored = activateSpan(parent)) {
        assertEquals(
            "response",
            producer.requestBodyAndHeader(
                "direct:outer", "payload", "application-header", "retained", String.class));
        assertSame(parent, activeSpan());
      }
    } finally {
      parent.finish();
      server.stop(0);
    }
    assertSame(original, Context.current());
    blockUntilTracesMatch(traces -> traces.stream().flatMap(List::stream).count() >= 4);
    List<DDSpan> spans = writer.stream().flatMap(List::stream).collect(Collectors.toList());
    assertEquals(4, spans.size(), "one caller, two routes, and one HTTP transport span");
    DDSpan outer = routeSpan(spans, "http-outer");
    DDSpan inner = routeSpan(spans, "http-inner");
    List<DDSpan> clients =
        spans.stream()
            .filter(span -> "apache-httpclient5".equals(String.valueOf(span.getTag("component"))))
            .collect(Collectors.toList());
    assertEquals(1, clients.size(), "transport instrumentation owns the single HTTP client span");
    DDSpan client = clients.get(0);
    assertEquals(parent.getSpanId(), outer.getParentId());
    assertEquals(outer.getSpanId(), inner.getParentId());
    assertEquals(inner.getSpanId(), client.getParentId());
    assertEquals(parent.getTraceId(), client.getTraceId());
    assertEquals(outer.getSpanId(), restoredOuter.get().getSpanId());
    assertFalse(client.isError());
    Headers headers = wireHeaders.get();
    assertNotNull(headers);
    assertEquals("retained", headers.getFirst("application-header"));
    assertEquals(
        singletonList(Long.toUnsignedString(client.getSpanId())),
        headers.get("x-datadog-parent-id"));
    assertEquals(
        singletonList(Long.toUnsignedString(client.getTraceId().toLong())),
        headers.get("x-datadog-trace-id"));
    assertEquals(2, routeSpans(2).size());
  }
}

package test.camel;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.impl.engine.HashMapHeadersMapFactory;
import org.apache.camel.support.DefaultExchange;
import org.apache.camel.support.DefaultMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CamelHeaderMapTest extends AbstractCamelTest {
  @Test
  void updatesDefaultHeadersInPlaceWithoutChangingACopiedExchange() throws Exception {
    AtomicReference<Map<String, Object>> before = new AtomicReference<>();
    AtomicReference<Exchange> copy = new AtomicReference<>();
    AtomicReference<String> routeParent = new AtomicReference<>();
    try (DefaultCamelContext context = new DefaultCamelContext()) {
      context.addRoutes(
          new RouteBuilder() {
            @Override
            public void configure() {
              from("direct:headers")
                  .routeId("headers")
                  .process(exchange -> {
                    exchange.getIn().setHeader("X-Datadog-Parent-Id", "123");
                    before.set(exchange.getIn().getHeaders());
                    copy.set(exchange.copy());
                    routeParent.set(Long.toUnsignedString(activeSpan().getSpanId()));
                  })
                  .to("mock:headers");
            }
          });
      context.start();
      try (ProducerTemplate producer = context.createProducerTemplate()) {
        Exchange result = producer.send("direct:headers", new DefaultExchange(context));
        assertNull(result.getException());
        assertSame(before.get(), result.getIn().getHeaders());
        assertNotSame(before.get(), copy.get().getIn().getHeaders());
        assertEquals(routeParent.get(), result.getIn().getHeader("x-datadog-parent-id"));
        assertEquals("123", copy.get().getIn().getHeader("x-datadog-parent-id"));
      }
    }
    assertEquals(1, routeSpans(1).size());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void preservesCaseSensitiveApplicationHeadersAndReplacesPropagationAliases(boolean immutable)
      throws Exception {
    AtomicReference<Map<String, Object>> original = new AtomicReference<>();
    AtomicReference<String> routeParent = new AtomicReference<>();
    try (DefaultCamelContext context = new DefaultCamelContext()) {
      context.getCamelContextExtension().setHeadersMapFactory(new HashMapHeadersMapFactory());
      context.addRoutes(
          new RouteBuilder() {
            @Override
            public void configure() {
              from("direct:headers")
                  .routeId("headers")
                  .process(exchange -> {
                    Map<String, Object> headers = new HashMap<>();
                    headers.put("Foo", "upper");
                    headers.put("foo", "lower");
                    headers.put("X-Datadog-Parent-Id", "123");
                    if (immutable) {
                      exchange.setIn(new InitiallyImmutableHeadersMessage(exchange, headers));
                    } else {
                      exchange.getIn().setHeaders(headers);
                    }
                    original.set(exchange.getIn().getHeaders());
                    routeParent.set(Long.toUnsignedString(activeSpan().getSpanId()));
                  })
                  .to("mock:headers");
            }
          });
      context.start();
      try (ProducerTemplate producer = context.createProducerTemplate()) {
        Exchange result = producer.send("direct:headers", new DefaultExchange(context));
        assertNull(result.getException());
        Map<String, Object> headers = result.getIn().getHeaders();
        assertEquals("upper", headers.get("Foo"));
        assertEquals("lower", headers.get("foo"));
        assertEquals(routeParent.get(), headers.get("x-datadog-parent-id"));
        assertEquals(
            1, headers.keySet().stream().filter("x-datadog-parent-id"::equalsIgnoreCase).count());
        assertNotSame(original.get(), headers);
        assertEquals("123", original.get().get("X-Datadog-Parent-Id"));
        assertEquals(3, original.get().size(), "custom header maps must not be partially mutated");
      }
    }
    assertEquals(1, routeSpans(1).size());
  }

  private static final class InitiallyImmutableHeadersMessage extends DefaultMessage {
    private boolean immutable;

    private InitiallyImmutableHeadersMessage(Exchange exchange, Map<String, Object> headers) {
      super(exchange);
      super.setHeaders(headers);
      immutable = true;
    }

    @Override
    public Map<String, Object> getHeaders() {
      Map<String, Object> headers = super.getHeaders();
      return immutable ? Collections.unmodifiableMap(headers) : headers;
    }

    @Override
    public void setHeaders(Map<String, Object> headers) {
      super.setHeaders(headers);
      immutable = false;
    }
  }
}

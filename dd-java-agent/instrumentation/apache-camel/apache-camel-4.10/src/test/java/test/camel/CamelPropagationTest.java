package test.camel;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import datadog.trace.api.DDTraceId;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.core.DDSpan;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.mock.MockEndpoint;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.Test;

class CamelPropagationTest extends AbstractCamelTest {
  @Test
  void extractsAnExternalParentFromMessageHeadersOnly() throws Exception {
    AtomicReference<AgentSpan> observed = new AtomicReference<>();
    try (DefaultCamelContext context = new DefaultCamelContext()) {
      context.addRoutes(
          new RouteBuilder() {
            @Override
            public void configure() {
              from("direct:incoming")
                  .routeId("incoming")
                  .process(exchange -> observed.set(activeSpan()));
            }
          });
      context.start();
      try (ProducerTemplate producer = context.createProducerTemplate()) {
        assertNull(activeSpan());
        Exchange incoming = new DefaultExchange(context);
        incoming.getMessage().setHeader("x-datadog-trace-id", "123456789");
        incoming.getMessage().setHeader("x-datadog-parent-id", "987654321");
        incoming.getMessage().setHeader("x-datadog-sampling-priority", "1");
        producer.send("direct:incoming", incoming);
        assertNull(incoming.getException());
        assertNull(activeSpan());
        assertNotNull(observed.get());
        assertEquals(DDTraceId.from("123456789"), observed.get().getTraceId());
        assertEquals(987654321L, ((DDSpan) observed.get()).getParentId());
      }
    }
    assertEquals(1, routeSpans(1).size());
  }

  @Test
  void transfersOnlyInjectedHeadersToAFreshExchangeOnAnUnrelatedThread() throws Exception {
    AtomicReference<AgentSpan> sending = new AtomicReference<>();
    AtomicReference<AgentSpan> receiving = new AtomicReference<>();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    try (DefaultCamelContext context = new DefaultCamelContext()) {
      context.addRoutes(
          new RouteBuilder() {
            @Override
            public void configure() {
              from("direct:outgoing")
                  .routeId("outgoing")
                  .process(exchange -> sending.set(activeSpan()))
                  .to("mock:wire");
              from("direct:incoming")
                  .routeId("incoming")
                  .process(exchange -> receiving.set(activeSpan()));
            }
          });
      context.start();
      try (ProducerTemplate producer = context.createProducerTemplate()) {
        producer.sendBody("direct:outgoing", "payload");
        assertNull(activeSpan());
        assertNotNull(sending.get());
        MockEndpoint wire = context.getEndpoint("mock:wire", MockEndpoint.class);
        assertEquals(1, wire.getReceivedExchanges().size());
        Map<String, Object> headers =
            new HashMap<>(wire.getReceivedExchanges().get(0).getMessage().getHeaders());
        assertEquals(sending.get().getTraceId().toString(), headers.get("x-datadog-trace-id"));
        assertEquals(
            Long.toUnsignedString(sending.get().getSpanId()), headers.get("x-datadog-parent-id"));
        Thread receiver =
            new Thread(
                () -> {
                  try {
                    assertNull(activeSpan(), "the receiver must start without local trace context");
                    Exchange incoming = new DefaultExchange(context);
                    assertEquals(
                        0,
                        incoming.getProperties().size(),
                        "no Exchange state crosses the boundary");
                    incoming.getMessage().setHeaders(headers);
                    incoming.getMessage().setBody("payload");
                    producer.send("direct:incoming", incoming);
                    assertNull(incoming.getException());
                    assertNull(
                        activeSpan(), "extracted context must not leak into the receiver thread");
                  } catch (Throwable error) {
                    failure.set(error);
                  }
                },
                "camel-remote-receiver");
        receiver.start();
        receiver.join(10_000);
        assertFalse(receiver.isAlive(), "the receiver must complete");
        assertNull(failure.get(), "header-only receiver assertions must succeed");
        assertNotNull(receiving.get());
        assertEquals(sending.get().getTraceId(), receiving.get().getTraceId());
        assertEquals(sending.get().getSpanId(), ((DDSpan) receiving.get()).getParentId());
      }
    }
    assertEquals(2, routeSpans(2).size(), "the wire transfer must retain exactly two route spans");
  }
}

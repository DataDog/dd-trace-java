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
import org.tabletest.junit.TableTest;

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

  @TableTest({
    "Scenario   | traceHeader        | parentHeader        | samplingHeader             ",
    "fresh      |                    |                     |                            ",
    "lowercase  | x-datadog-trace-id | x-datadog-parent-id | x-datadog-sampling-priority",
    "title case | X-Datadog-Trace-Id | X-Datadog-Parent-Id | X-Datadog-Sampling-Priority",
    "mixed case | x-DaTaDoG-tRaCe-Id | X-dAtAdOg-PaReNt-iD | x-DaTaDoG-sAmPlInG-pRiOrItY"
  })
  void transfersOnlyInjectedHeadersToAFreshExchangeOnAnUnrelatedThread(
      String traceHeader, String parentHeader, String samplingHeader) throws Exception {
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
        assertNull(activeSpan());
        Exchange outgoing = new DefaultExchange(context);
        outgoing.getMessage().setBody("payload");
        outgoing.getMessage().setHeader("application-header", "retained");
        if (traceHeader != null) {
          outgoing.getMessage().setHeader(traceHeader, "123456789");
          outgoing.getMessage().setHeader(parentHeader, "987654321");
          outgoing.getMessage().setHeader(samplingHeader, "1");
        }
        producer.send("direct:outgoing", outgoing);
        assertNull(outgoing.getException());
        assertNull(activeSpan());
        assertNotNull(sending.get());
        if (traceHeader != null) {
          assertEquals(DDTraceId.from("123456789"), sending.get().getTraceId());
          assertEquals(987654321L, ((DDSpan) sending.get()).getParentId());
        }
        MockEndpoint wire = context.getEndpoint("mock:wire", MockEndpoint.class);
        assertEquals(1, wire.getReceivedExchanges().size());
        Map<String, Object> headers =
            new HashMap<>(wire.getReceivedExchanges().get(0).getMessage().getHeaders());
        assertEquals("retained", headers.get("application-header"));
        assertPropagatedHeader(
            headers, "x-datadog-trace-id", sending.get().getTraceId().toString());
        assertPropagatedHeader(
            headers, "x-datadog-parent-id", Long.toUnsignedString(sending.get().getSpanId()));
        if (samplingHeader != null) {
          assertPropagatedHeader(headers, "x-datadog-sampling-priority", "1");
        }
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
        assertNull(activeSpan(), "the header transfer must not leave caller context active");
      }
    }
    assertEquals(2, routeSpans(2).size(), "the wire transfer must retain exactly two route spans");
  }

  private static void assertPropagatedHeader(
      Map<String, Object> headers, String name, String expected) {
    assertEquals(
        1L,
        headers.keySet().stream().filter(name::equalsIgnoreCase).count(),
        "propagation must leave exactly one " + name + " header");
    for (Map.Entry<String, Object> header : headers.entrySet()) {
      if (name.equalsIgnoreCase(header.getKey())) {
        assertEquals(expected, header.getValue(), "propagated value of " + header.getKey());
      }
    }
  }
}

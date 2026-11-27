package test.camel;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import datadog.context.Context;
import datadog.context.ContextScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.core.DDSpan;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.bean.BeanComponent;
import org.apache.camel.component.direct.DirectComponent;
import org.apache.camel.component.log.LogComponent;
import org.apache.camel.component.mock.MockComponent;
import org.apache.camel.component.mock.MockEndpoint;
import org.apache.camel.impl.DefaultCamelContext;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class CamelLocalSendTest extends AbstractCamelTest {
  @Test
  void dynamicDestinationsFilterEachTarget() throws Exception {
    AtomicReference<AgentSpan> outgoing = new AtomicReference<>();
    Context original = Context.current();
    try (DefaultCamelContext context = new DefaultCamelContext()) {
      context.addComponent("custom", new MockComponent());
      context.addRoutes(
          new RouteBuilder() {
            @Override
            public void configure() {
              from("direct:start")
                  .routeId("outer")
                  .process(exchange -> outgoing.set(activeSpan()))
                  .toD("${header.destination}");
              from("direct:inner").routeId("inner").setBody(body());
            }
          });
      context.getEndpoint("custom:wire");
      context.start();
      try (ProducerTemplate producer = context.createProducerTemplate()) {
        for (String destination : new String[] {"direct:inner", "custom:wire", "direct:inner"}) {
          Exchange result =
              producer.request(
                  "direct:start",
                  exchange -> {
                    exchange.getMessage().setBody("payload");
                    exchange.getMessage().setHeader("destination", destination);
                  });
          assertNull(result.getException());
          assertEquals("payload", result.getMessage().getBody());
          if ("direct:inner".equals(destination)) {
            assertNull(result.getMessage().getHeader("x-datadog-parent-id"));
          } else {
            assertEquals(
                Long.toUnsignedString(outgoing.get().getSpanId()),
                result.getMessage().getHeader("x-datadog-parent-id"));
          }
        }
      }
    }
    assertSame(original, Context.current());
    assertEquals(5, routeSpans(5).size());
  }

  @TableTest({
    "Scenario      | localEndpoint                | customScheme  | routeCount",
    "nested route  | direct:local                 | direct-custom | 2         ",
    "bean call     | bean:local?method=echo       | bean-custom   | 1         ",
    "logging       | log:local?level=DEBUG        | log-custom    | 1         ",
    "aliased route | local-direct:local           | direct-custom | 2         ",
    "aliased bean  | local-bean:local?method=echo | bean-custom   | 1         ",
    "aliased log   | local-log:local?level=DEBUG  | log-custom    | 1         "
  })
  void localSendsLeaveHeadersAloneAndCustomEndpointsStillPropagate(
      String localEndpoint, String customScheme, int routeCount) throws Exception {
    AtomicReference<AgentSpan> outer = new AtomicReference<>();
    AtomicReference<Map<String, Object>> localHeaders = new AtomicReference<>();
    Map<String, Object> wireHeaders;
    Context original = Context.current();
    AgentSpan parent = startSpan("test", "local-send-parent");
    try (DefaultCamelContext context = new DefaultCamelContext()) {
      context.getRegistry().bind("local", new EchoBean());
      context.addComponent("local-direct", new DirectComponent());
      context.addComponent("local-bean", new BeanComponent());
      context.addComponent("local-log", new LogComponent());
      context.addComponent(customScheme, new MockComponent());
      String wireUri = customScheme + ":wire";
      context.addRoutes(
          new RouteBuilder() {
            @Override
            public void configure() {
              from("direct:start")
                  .routeId("outer")
                  .process(exchange -> outer.set(activeSpan()))
                  .to(localEndpoint)
                  .process(exchange -> {
                    localHeaders.set(new HashMap<>(exchange.getMessage().getHeaders()));
                    assertSame(outer.get(), activeSpan());
                  })
                  .to(wireUri);
              if (routeCount == 2) {
                from(localEndpoint).routeId("inner").setBody(body());
              }
            }
          });
      context.start();
      try (ProducerTemplate producer = context.createProducerTemplate();
          ContextScope ignored = activateSpan(parent)) {
        assertEquals(
            "payload",
            producer.requestBodyAndHeader("direct:start", "payload", "business", "retained"));
        assertSame(parent, activeSpan());
      }
      MockEndpoint wire = context.getEndpoint(wireUri, MockEndpoint.class);
      assertEquals(1, wire.getReceivedExchanges().size());
      wireHeaders = new HashMap<>(wire.getReceivedExchanges().get(0).getMessage().getHeaders());
    } finally {
      parent.finish();
    }
    assertSame(original, Context.current());
    assertEquals("retained", localHeaders.get().get("business"));
    assertNull(localHeaders.get().get("x-datadog-trace-id"));
    assertNull(localHeaders.get().get("x-datadog-parent-id"));
    assertNull(localHeaders.get().get("traceparent"));
    assertEquals("retained", wireHeaders.get("business"));
    assertEquals(
        Long.toUnsignedString(outer.get().getSpanId()), wireHeaders.get("x-datadog-parent-id"));
    assertEquals(outer.get().getTraceId().toString(), wireHeaders.get("x-datadog-trace-id"));
    List<DDSpan> routes = routeSpans(routeCount);
    assertEquals(routeCount, routes.size());
    assertEquals(parent.getSpanId(), routeSpan(routes, "outer").getParentId());
    if (routeCount == 2) {
      assertEquals(outer.get().getSpanId(), routeSpan(routes, "inner").getParentId());
    }
  }

  public static final class EchoBean {
    public String echo(String body) {
      return body;
    }
  }
}

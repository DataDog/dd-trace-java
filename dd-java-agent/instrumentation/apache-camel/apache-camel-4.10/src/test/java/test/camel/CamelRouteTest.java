package test.camel;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.CAMEL_ENDPOINT_SCHEME;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.CAMEL_ROUTE_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.context.Context;
import datadog.context.ContextScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.core.DDSpan;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.impl.DefaultCamelContext;
import org.junit.jupiter.api.Test;

class CamelRouteTest extends AbstractCamelTest {
  @Test
  void tracesRouteWithoutApplicationTracingAdapter() throws Exception {
    AtomicReference<AgentSpan> observed = new AtomicReference<>();
    Context original = Context.current();
    try (DefaultCamelContext context = new DefaultCamelContext()) {
      context.addRoutes(
          new RouteBuilder() {
            @Override
            public void configure() {
              from("direct:start")
                  .routeId("simple")
                  .process(exchange -> observed.set(activeSpan()))
                  .setBody(constant("done"));
            }
          });
      context.start();
      try (ProducerTemplate producer = context.createProducerTemplate()) {
        assertEquals("done", producer.requestBody("direct:start", "input", String.class));
      }
    }
    assertSame(original, Context.current());
    assertNotNull(observed.get(), "native Camel instrumentation must activate a route span");
    assertEquals("camel.route", observed.get().getOperationName().toString());
    List<DDSpan> routes = routeSpans(1);
    assertEquals(1, routes.size(), "a single route must produce exactly one route span");
    DDSpan route = routes.get(0);
    assertEquals("simple", route.getResourceName().toString());
    assertEquals(0, route.getParentId());
    assertEquals("camel", route.getTag("component").toString());
    assertEquals("simple", route.getTag(CAMEL_ROUTE_ID));
    assertEquals("direct", route.getTag(CAMEL_ENDPOINT_SCHEME));
    assertFalse(route.isError());
  }

  @Test
  void nestedDirectRoutesRetainParentageAndRestoreTheCaller() throws Exception {
    AtomicReference<AgentSpan> outer = new AtomicReference<>();
    AtomicReference<AgentSpan> inner = new AtomicReference<>();
    AtomicReference<AgentSpan> after = new AtomicReference<>();
    AgentSpan parent = startSpan("test", "parent");
    Context original = Context.current();
    try (DefaultCamelContext context = new DefaultCamelContext()) {
      context.addRoutes(
          new RouteBuilder() {
            @Override
            public void configure() {
              from("direct:start")
                  .routeId("outer")
                  .process(exchange -> outer.set(activeSpan()))
                  .to("direct:inner")
                  .process(exchange -> after.set(activeSpan()));
              from("direct:inner").routeId("inner").process(exchange -> inner.set(activeSpan()));
            }
          });
      context.start();
      try (ProducerTemplate producer = context.createProducerTemplate();
          ContextScope ignored = activateSpan(parent)) {
        assertEquals("input", producer.requestBody("direct:start", "input", String.class));
        assertSame(parent, activeSpan());
      }
    } finally {
      parent.finish();
    }
    assertSame(original, Context.current());
    assertSame(outer.get(), after.get(), "the nested route must restore the outer route context");
    List<DDSpan> routes = routeSpans(2);
    assertEquals(2, routes.size());
    DDSpan outerSpan = named(routes, "outer");
    DDSpan innerSpan = named(routes, "inner");
    assertEquals(parent.getTraceId(), outerSpan.getTraceId());
    assertEquals(parent.getSpanId(), outerSpan.getParentId());
    assertEquals(outerSpan.getTraceId(), innerSpan.getTraceId());
    assertEquals(outerSpan.getSpanId(), innerSpan.getParentId());
    assertEquals(outerSpan.getSpanId(), outer.get().getSpanId());
    assertEquals(innerSpan.getSpanId(), inner.get().getSpanId());
  }

  @Test
  void sedaConnectsAQueuedExchangeToItsSendingRoute() throws Exception {
    AtomicReference<AgentSpan> queued = new AtomicReference<>();
    AtomicReference<AgentSpan> resumed = new AtomicReference<>();
    Context original = Context.current();
    try (DefaultCamelContext context = new DefaultCamelContext()) {
      context.addRoutes(
          new RouteBuilder() {
            @Override
            public void configure() {
              from("direct:start")
                  .routeId("sending")
                  .to("seda:work?waitForTaskToComplete=Always")
                  .process(exchange -> resumed.set(activeSpan()));
              from("seda:work")
                  .routeId("queued")
                  .process(exchange -> queued.set(activeSpan()))
                  .setBody(constant("processed"));
            }
          });
      // Start SEDA's long-lived worker before attaching any request context.
      context.start();
      AgentSpan parent = startSpan("test", "parent");
      try (ProducerTemplate producer = context.createProducerTemplate();
          ContextScope ignored = activateSpan(parent)) {
        assertEquals("processed", producer.requestBody("direct:start", "input", String.class));
        assertSame(parent, activeSpan());
      } finally {
        parent.finish();
      }
      List<DDSpan> routes = routeSpans(2);
      assertEquals(2, routes.size());
      DDSpan sending = named(routes, "sending");
      DDSpan receiving = named(routes, "queued");
      assertEquals(parent.getSpanId(), sending.getParentId());
      assertEquals(sending.getTraceId(), receiving.getTraceId());
      assertEquals(sending.getSpanId(), receiving.getParentId());
      assertEquals(receiving.getSpanId(), queued.get().getSpanId());
      assertEquals(sending.getSpanId(), resumed.get().getSpanId());
    }
    assertSame(original, Context.current());
  }

  @Test
  void failedExchangePreservesItsExceptionAndMarksTheRoute() throws Exception {
    IllegalStateException failure = new IllegalStateException("route failure");
    Context original = Context.current();
    try (DefaultCamelContext context = new DefaultCamelContext()) {
      context.addRoutes(
          new RouteBuilder() {
            @Override
            public void configure() {
              errorHandler(noErrorHandler());
              from("direct:fail")
                  .routeId("failed")
                  .process(exchange -> {
                    throw failure;
                  });
            }
          });
      context.start();
      try (ProducerTemplate producer = context.createProducerTemplate()) {
        Exchange result =
            producer.request("direct:fail", exchange -> exchange.getIn().setBody("input"));
        assertSame(failure, result.getException());
      }
    }
    assertSame(original, Context.current());
    DDSpan route = named(routeSpans(1), "failed");
    assertTrue(route.isError());
    assertEquals(IllegalStateException.class.getName(), route.getTag("error.type"));
    assertEquals("route failure", route.getTag("error.message"));
  }

  @Test
  void handledFailureAndEarlyStopCompleteTheirRoutes() throws Exception {
    AtomicReference<AgentSpan> recovered = new AtomicReference<>();
    Context original = Context.current();
    try (DefaultCamelContext context = new DefaultCamelContext()) {
      context.addRoutes(
          new RouteBuilder() {
            @Override
            public void configure() {
              onException(IllegalStateException.class)
                  .handled(true)
                  .process(exchange -> recovered.set(activeSpan()))
                  .setBody(constant("recovered"));
              from("direct:handled")
                  .routeId("handled")
                  .process(exchange -> {
                    throw new IllegalStateException("handled failure");
                  });
              from("direct:stop")
                  .routeId("stopped")
                  .setBody(constant("stopped"))
                  .stop()
                  .setBody(constant("unreachable"));
            }
          });
      context.start();
      try (ProducerTemplate producer = context.createProducerTemplate()) {
        assertEquals("recovered", producer.requestBody("direct:handled", "input", String.class));
        assertEquals("stopped", producer.requestBody("direct:stop", "input", String.class));
      }
    }
    assertSame(original, Context.current());
    List<DDSpan> routes = routeSpans(2);
    assertEquals(2, routes.size());
    assertEquals(named(routes, "handled").getSpanId(), recovered.get().getSpanId());
    assertFalse(named(routes, "stopped").isError());
  }

  private static DDSpan named(List<DDSpan> spans, String name) {
    return spans.stream()
        .filter(span -> name.contentEquals(span.getResourceName()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Missing route " + name));
  }
}

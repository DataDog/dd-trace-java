package test.camel;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import datadog.context.ContextScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.camel.CamelContext;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.spi.CamelTracingService;
import org.apache.camel.support.service.ServiceSupport;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class CamelNativeTracingTest extends AbstractCamelTest {
  @TableTest({
    "Registration        | afterStart | warmup",
    "before start        | false      | false ",
    "after start         | true       | false ",
    "after first message | true       | true  "
  })
  void registeredTracingServiceOwnsRouteTracingAndPropagation(boolean afterStart, boolean warmup)
      throws Exception {
    AtomicReference<AgentSpan> observed = new AtomicReference<>();
    AtomicReference<Map<String, Object>> observedHeaders = new AtomicReference<>();
    AgentSpan parent = startSpan("test", "parent");
    try (DefaultCamelContext context = new DefaultCamelContext()) {
      context.addRoutes(
          new RouteBuilder() {
            @Override
            public void configure() {
              from("direct:native")
                  .routeId("native")
                  .process(exchange -> observed.set(activeSpan()))
                  .to("mock:wire")
                  .process(exchange -> observedHeaders.set(exchange.getMessage().getHeaders()));
            }
          });
      NativeTracingService service = new NativeTracingService();
      if (!afterStart) {
        context.addService(service);
      }
      context.start();
      if (warmup) {
        try (ProducerTemplate producer = context.createProducerTemplate();
            ContextScope ignored = activateSpan(parent)) {
          producer.requestBody("direct:native", "warmup", String.class);
        }
      }
      if (afterStart) {
        context.addService(service);
      }
      assertSame(service, context.hasService(CamelTracingService.class));
      try (ProducerTemplate producer = context.createProducerTemplate();
          ContextScope ignored = activateSpan(parent)) {
        assertEquals("payload", producer.requestBody("direct:native", "payload", String.class));
        assertSame(parent, activeSpan());
      }
      assertSame(parent, observed.get(), "the installed tracing service owns route tracing");
      assertFalse(
          observedHeaders.get().keySet().stream()
              .anyMatch(key -> key.toLowerCase(java.util.Locale.ROOT).startsWith("x-datadog-")),
          "Camel instrumentation must not inject Datadog headers while native tracing owns the route");
      assertNull(observedHeaders.get().get("traceparent"));
    } finally {
      parent.finish();
    }
    writer.waitForTraces(1);
    assertEquals(
        warmup ? 1 : 0,
        writer.stream()
            .flatMap(java.util.List::stream)
            .filter(span -> "camel.route".contentEquals(span.getOperationName()))
            .count());
  }

  @TableTest({
    "Registration | deferred",
    "add service  | false   ",
    "defer start  | true    "
  })
  void cachesDetectionUntilTracingServicesChange(boolean deferred) throws Exception {
    AtomicReference<AgentSpan> observed = new AtomicReference<>();
    AgentSpan parent = startSpan("test", "parent");
    try (CountingCamelContext context = new CountingCamelContext()) {
      context.addRoutes(
          new RouteBuilder() {
            @Override
            public void configure() {
              from("direct:changes")
                  .routeId("changes")
                  .process(exchange -> observed.set(activeSpan()));
            }
          });
      context.start();
      context.tracingLookups.set(0);
      try (ProducerTemplate producer = context.createProducerTemplate();
          ContextScope ignored = activateSpan(parent)) {
        producer.requestBody("direct:changes", "first");
        assertNotSame(parent, observed.get());
        producer.requestBody("direct:changes", "second");
        assertNotSame(parent, observed.get());
        assertEquals(1, context.tracingLookups.get());

        NativeTracingService first = new NativeTracingService();
        NativeTracingService second = new NativeTracingService();
        if (deferred) {
          context.deferStartService(first, true);
        } else {
          context.addService(first);
        }
        context.addService(second);
        producer.requestBody("direct:changes", "native");
        assertSame(parent, observed.get());
        assertEquals(2, context.tracingLookups.get());

        context.removeService(first);
        producer.requestBody("direct:changes", "still native");
        assertSame(parent, observed.get());
        assertEquals(3, context.tracingLookups.get());

        context.removeService(second);
        producer.requestBody("direct:changes", "resumed");
        assertNotSame(parent, observed.get());
        producer.requestBody("direct:changes", "still resumed");
        assertNotSame(parent, observed.get());
        assertEquals(4, context.tracingLookups.get());
      }
    } finally {
      parent.finish();
    }
    assertEquals(4, routeSpans(4).size());
  }

  @Test
  void detectsServicesAgainAfterContextRestart() throws Exception {
    AtomicReference<AgentSpan> observed = new AtomicReference<>();
    boolean nativeRestored;
    AgentSpan parent = startSpan("test", "parent");
    try (DefaultCamelContext context = new DefaultCamelContext()) {
      context.addRoutes(
          new RouteBuilder() {
            @Override
            public void configure() {
              from("direct:restart")
                  .routeId("restart")
                  .process(exchange -> observed.set(activeSpan()));
            }
          });
      context.start();
      context.addService(new NativeTracingService());
      try (ContextScope ignored = activateSpan(parent)) {
        try (ProducerTemplate producer = context.createProducerTemplate()) {
          producer.requestBody("direct:restart", "native");
          assertSame(parent, observed.get());
        }
        context.stop();
        assertNull(context.hasService(CamelTracingService.class));
        context.start();
        nativeRestored = context.hasService(CamelTracingService.class) != null;
        try (ProducerTemplate producer = context.createProducerTemplate()) {
          producer.requestBody("direct:restart", "restarted");
          assertEquals(nativeRestored, observed.get() == parent);
        }
      }
    } finally {
      parent.finish();
    }
    writer.waitForTraces(1);
    int expectedRoutes = nativeRestored ? 0 : 1;
    assertEquals(expectedRoutes, routeSpans(expectedRoutes).size());
  }

  private static final class CountingCamelContext extends DefaultCamelContext {
    final AtomicInteger tracingLookups = new AtomicInteger();

    @Override
    public <T> T hasService(Class<T> type) {
      if (type == CamelTracingService.class) {
        tracingLookups.incrementAndGet();
      }
      return super.hasService(type);
    }
  }

  // Deliberately does not extend either of Camel's concrete Tracer base classes.
  private static final class NativeTracingService extends ServiceSupport
      implements CamelTracingService {
    private CamelContext context;

    @Override
    public CamelContext getCamelContext() {
      return context;
    }

    @Override
    public void setCamelContext(CamelContext context) {
      this.context = context;
    }
  }
}

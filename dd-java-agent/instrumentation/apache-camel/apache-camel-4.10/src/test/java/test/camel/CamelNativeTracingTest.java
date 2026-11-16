package test.camel;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import datadog.context.ContextScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.camel.CamelContext;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.spi.CamelTracingService;
import org.apache.camel.support.service.ServiceSupport;
import org.tabletest.junit.TableTest;

class CamelNativeTracingTest extends AbstractCamelTest {
  @TableTest({
    "Registration | afterStart",
    "before start | false     ",
    "after start  | true      "
  })
  void registeredTracingServiceOwnsRouteTracingAndPropagation(boolean afterStart) throws Exception {
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
        0,
        writer.stream()
            .flatMap(java.util.List::stream)
            .filter(span -> "camel.route".contentEquals(span.getOperationName()))
            .count());
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

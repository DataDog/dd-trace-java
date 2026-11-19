package test.camel;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.core.DDSpan;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.impl.engine.DefaultUnitOfWork;
import org.apache.camel.spi.Synchronization;
import org.apache.camel.spi.UnitOfWorkFactory;
import org.junit.jupiter.api.Test;

class CamelLifecycleTest extends AbstractCamelTest {
  @Test
  void completedNestedRoutesReleaseTheirFallbackWhileTheOuterRouteIsLive() throws Exception {
    int invocations = 32;
    List<Integer> retainedAfterNestedRoutes = new ArrayList<>();
    AtomicReference<AgentSpan> outer = new AtomicReference<>();
    AtomicReference<RecordingUnitOfWork> unitOfWork = new AtomicReference<>();
    try (DefaultCamelContext context = new DefaultCamelContext()) {
      context
          .getCamelContextExtension()
          .addContextPlugin(
              UnitOfWorkFactory.class,
              exchange -> {
                RecordingUnitOfWork created = new RecordingUnitOfWork(exchange);
                assertTrue(unitOfWork.compareAndSet(null, created), "direct routes share one UoW");
                return created;
              });
      context.addRoutes(
          new RouteBuilder() {
            @Override
            public void configure() {
              from("direct:outer")
                  .routeId("outer")
                  .process(exchange -> outer.set(activeSpan()))
                  .loop(invocations)
                  .to("direct:inner")
                  .process(exchange -> {
                    assertSame(outer.get(), activeSpan());
                    assertSame(unitOfWork.get(), exchange.getUnitOfWork());
                    assertFalse(unitOfWork.get().done, "the outer exchange must still be live");
                    retainedAfterNestedRoutes.add(unitOfWork.get().retainedRouteSynchronizations());
                  })
                  .end();
              from("direct:inner").routeId("inner").setBody(constant("done"));
            }
          });
      context.start();
      try (ProducerTemplate producer = context.createProducerTemplate()) {
        Exchange result =
            producer.request("direct:outer", exchange -> exchange.getIn().setBody("input"));
        assertNull(result.getException());
        assertEquals("done", result.getMessage().getBody());
      }
    }
    assertEquals(invocations, retainedAfterNestedRoutes.size());
    assertEquals(invocations + 1, unitOfWork.get().routeSynchronizations.size());
    for (int retained : retainedAfterNestedRoutes) {
      assertEquals(1, retained, "only the live outer route should retain its fallback");
    }
    assertEquals(0, unitOfWork.get().retainedRouteSynchronizations());
    List<DDSpan> routes = routeSpans(invocations + 1);
    assertEquals(invocations + 1, routes.size());
    assertEquals(
        invocations,
        routes.stream().filter(span -> "inner".contentEquals(span.getResourceName())).count());
    for (DDSpan route : routes) {
      assertFalse(route.isError());
      assertEquals(outer.get().getTraceId(), route.getTraceId());
      assertEquals(
          "outer".contentEquals(route.getResourceName()) ? 0 : outer.get().getSpanId(),
          route.getParentId());
    }
  }

  @Test
  void unitOfWorkFallbackFinishesOnceWhenStoppingGuardSkipsRoutePolicyCompletion()
      throws Exception {
    AtomicBoolean stopping = new AtomicBoolean();
    AtomicReference<RecordingUnitOfWork> unitOfWork = new AtomicReference<>();
    AtomicInteger retainedAtUnitOfWorkCompletion = new AtomicInteger();
    try (DefaultCamelContext context =
        new DefaultCamelContext() {
          @Override
          public boolean isStopping() {
            return stopping.get() || super.isStopping();
          }
        }) {
      context
          .getCamelContextExtension()
          .addContextPlugin(
              UnitOfWorkFactory.class,
              exchange -> {
                RecordingUnitOfWork created =
                    new RecordingUnitOfWork(exchange) {
                      @Override
                      public void done(Exchange completed) {
                        retainedAtUnitOfWorkCompletion.set(retainedRouteSynchronizations());
                        super.done(completed);
                      }
                    };
                unitOfWork.set(created);
                return created;
              });
      context.addRoutes(
          new RouteBuilder() {
            @Override
            public void configure() {
              from("direct:stopping").routeId("stopping").process(exchange -> stopping.set(true));
            }
          });
      context.start();
      try (ProducerTemplate producer = context.createProducerTemplate()) {
        Exchange result;
        try {
          result =
              producer.request("direct:stopping", exchange -> exchange.getIn().setBody("input"));
        } finally {
          stopping.set(false);
        }
        assertNull(result.getException());
        assertEquals(1, retainedAtUnitOfWorkCompletion.get(), "the UoW must execute the fallback");
        assertTrue(unitOfWork.get().done);
        assertEquals(1, unitOfWork.get().routeSynchronizations.size());
        Synchronization fallback = unitOfWork.get().routeSynchronizations.get(0);
        fallback.onComplete(result);
        result.setException(new IllegalStateException("late duplicate failure"));
        fallback.onFailure(result);
      }
    }
    List<DDSpan> routes = routeSpans(1);
    assertEquals(1, routes.size(), "fallback and duplicate callbacks must finish one route span");
    assertFalse(
        routes.get(0).isError(), "a late duplicate failure must not alter the finished span");
  }

  private static class RecordingUnitOfWork extends DefaultUnitOfWork {
    final List<Synchronization> routeSynchronizations = new ArrayList<>();
    boolean done;

    RecordingUnitOfWork(Exchange exchange) {
      super(exchange);
    }

    @Override
    public void addSynchronization(Synchronization synchronization) {
      super.addSynchronization(synchronization);
      if ("datadog.trace.instrumentation.camel.CamelState"
          .equals(synchronization.getClass().getName())) {
        routeSynchronizations.add(synchronization);
      }
    }

    int retainedRouteSynchronizations() {
      return (int) routeSynchronizations.stream().filter(this::containsSynchronization).count();
    }

    @Override
    public void done(Exchange exchange) {
      super.done(exchange);
      done = true;
    }
  }
}

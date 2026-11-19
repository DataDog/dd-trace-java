package test.camel;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.context.Context;
import datadog.context.ContextScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.core.DDSpan;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.camel.AsyncCallback;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.AsyncProcessorSupport;
import org.junit.jupiter.api.Test;

class CamelAsyncTest extends AbstractCamelTest {
  @Test
  void asyncCompletionKeepsTheRouteOpenAndRestoresTheCallbackThread() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicReference<AsyncCallback> callback = new AtomicReference<>();
    AtomicReference<Exchange> pending = new AtomicReference<>();
    AtomicReference<AgentSpan> before = new AtomicReference<>();
    AtomicReference<AgentSpan> after = new AtomicReference<>();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Thread completion =
        new Thread(
            () -> {
              Context original = Context.current();
              try {
                assertTrue(release.await(10, SECONDS));
                pending.get().getMessage().setBody("completed");
                callback.get().done(false);
                assertSame(
                    original, Context.current(), "callback must restore its worker's context");
              } catch (Throwable error) {
                failure.set(error);
              }
            },
            "camel-external-completion");
    // This worker predates the request and is not an Executor instrumented at task submission.
    completion.start();
    AgentSpan parent = startSpan("test", "parent");
    try (DefaultCamelContext context = new DefaultCamelContext()) {
      context.addRoutes(
          new RouteBuilder() {
            @Override
            public void configure() {
              from("direct:async")
                  .routeId("async")
                  .process(
                      new AsyncProcessorSupport() {
                        @Override
                        public boolean process(Exchange exchange, AsyncCallback done) {
                          before.set(activeSpan());
                          pending.set(exchange);
                          callback.set(done);
                          entered.countDown();
                          return false;
                        }
                      })
                  .process(exchange -> after.set(activeSpan()));
            }
          });
      context.start();
      try (ProducerTemplate producer = context.createProducerTemplate()) {
        // Erase the capture type so synthetic lambda signatures do not expose bootstrap classes.
        Object callerParent = parent;
        Thread caller =
            new Thread(
                () -> {
                  Context original = Context.current();
                  try (ContextScope ignored = activateSpan((AgentSpan) callerParent)) {
                    assertEquals(
                        "completed", producer.requestBody("direct:async", "input", String.class));
                    assertSame(callerParent, activeSpan());
                  } catch (Throwable error) {
                    failure.set(error);
                  } finally {
                    if (Context.current() != original) {
                      failure.set(new AssertionError("caller context leaked"));
                    }
                  }
                },
                "camel-request");
        caller.start();
        try {
          assertTrue(entered.await(10, SECONDS));
          assertNotNull(before.get());
          assertEquals(
              0,
              ((DDSpan) before.get()).getDurationNano(),
              "an incomplete async route must remain open");
        } finally {
          release.countDown();
          caller.join(10_000);
          completion.join(10_000);
        }
        assertFalse(caller.isAlive(), "request must finish");
        assertFalse(completion.isAlive(), "callback must finish");
        assertNull(failure.get(), "application request and callback assertions must succeed");
        assertTrue(
            ((DDSpan) before.get()).getDurationNano() > 0,
            "the route must finish on callback completion, before Camel shuts down");
      }
    } finally {
      release.countDown();
      completion.join(10_000);
      parent.finish();
    }
    assertNull(failure.get(), "application request and callback assertions must succeed");
    assertSame(
        before.get(), after.get(), "downstream processing must reactivate the route context");
    List<DDSpan> routes = routeSpans(1);
    assertEquals(1, routes.size());
    assertEquals(parent.getTraceId(), routes.get(0).getTraceId());
    assertEquals(parent.getSpanId(), routes.get(0).getParentId());
  }

  @Test
  void interleavedSedaMessagesKeepTheirOwnParentsAndDoNotLeakIntoTheNextMessage() throws Exception {
    Map<String, AgentSpan> observed = new ConcurrentHashMap<>();
    Map<String, AgentSpan> parents = new HashMap<>();
    CountDownLatch processed = new CountDownLatch(7);
    try (DefaultCamelContext context = new DefaultCamelContext()) {
      context.addRoutes(
          new RouteBuilder() {
            @Override
            public void configure() {
              from("seda:messages?concurrentConsumers=2")
                  .routeId("queued")
                  .process(exchange -> {
                    observed.put(exchange.getIn().getBody(String.class), activeSpan());
                    processed.countDown();
                  });
            }
          });
      context.start();
      try (ProducerTemplate producer = context.createProducerTemplate()) {
        for (int i = 0; i < 6; i++) {
          String message = "message-" + i;
          AgentSpan parent = startSpan("test", message);
          parents.put(message, parent);
          try (ContextScope ignored = activateSpan(parent)) {
            producer.sendBody("seda:messages", message);
            assertSame(parent, activeSpan());
          } finally {
            parent.finish();
          }
        }
        producer.sendBody("seda:messages", "unparented");
        assertTrue(processed.await(10, SECONDS));
      }
    }
    List<DDSpan> routes = routeSpans(7);
    assertEquals(7, routes.size(), "each message must have exactly one route span");
    assertEquals(7, routes.stream().map(DDSpan::getSpanId).distinct().count());
    for (Map.Entry<String, AgentSpan> item : parents.entrySet()) {
      AgentSpan active = observed.get(item.getKey());
      assertNotNull(active);
      long activeId = active.getSpanId();
      DDSpan route =
          routes.stream()
              .filter(span -> span.getSpanId() == activeId)
              .findFirst()
              .orElseThrow(AssertionError::new);
      assertEquals(item.getValue().getTraceId(), route.getTraceId());
      assertEquals(item.getValue().getSpanId(), route.getParentId());
    }
    AgentSpan unparented = observed.get("unparented");
    long unparentedId = unparented.getSpanId();
    DDSpan last =
        routes.stream()
            .filter(span -> span.getSpanId() == unparentedId)
            .findFirst()
            .orElseThrow(AssertionError::new);
    assertEquals(0, last.getParentId(), "a reused SEDA worker must not retain a previous parent");
  }
}

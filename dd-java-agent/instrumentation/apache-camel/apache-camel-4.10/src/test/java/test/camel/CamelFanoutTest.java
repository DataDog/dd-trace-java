package test.camel;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.context.Context;
import datadog.context.ContextScope;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.core.DDSpan;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.model.RouteDefinition;
import org.tabletest.junit.TableTest;

class CamelFanoutTest extends AbstractInstrumentationTest {
  @TableTest({
    "Scenario             | split | parallel",
    "sequential split     | true  | false   ",
    "parallel split       | true  | true    ",
    "sequential multicast | false | false   ",
    "parallel multicast   | false | true    "
  })
  void branchesKeepDistinctRouteContextsAndRestoreTheEnclosingRoute(boolean split, boolean parallel)
      throws Exception {
    Map<String, Long> observed = new ConcurrentHashMap<>();
    Map<String, Long> threads = new ConcurrentHashMap<>();
    CountDownLatch branches = new CountDownLatch(2);
    AtomicLong outerBefore = new AtomicLong();
    AtomicLong outerAfter = new AtomicLong();
    Context original = Context.current();
    AgentSpan parent = startSpan("test", "fanout-parent");
    try (DefaultCamelContext context = new DefaultCamelContext()) {
      context.addRoutes(
          new RouteBuilder() {
            @Override
            public void configure() {
              RouteDefinition outer =
                  from("direct:fanout")
                      .routeId("fanout")
                      .process(exchange -> outerBefore.set(activeSpan().getSpanId()));
              if (split) {
                outer
                    .split(body().tokenize(","))
                    .parallelProcessing(parallel)
                    .toD("direct:${body}")
                    .end();
              } else {
                outer
                    .multicast()
                    .parallelProcessing(parallel)
                    .to("direct:left", "direct:right")
                    .end();
              }
              outer.process(exchange -> outerAfter.set(activeSpan().getSpanId()));
              for (String name : new String[] {"left", "right"}) {
                from("direct:" + name)
                    .routeId(name)
                    .process(exchange -> {
                      long routeId = activeSpan().getSpanId();
                      assertNull(observed.put(name, routeId), "each branch executes once");
                      threads.put(name, Thread.currentThread().getId());
                      branches.countDown();
                      if (parallel) {
                        assertTrue(branches.await(10, SECONDS), "both branches must overlap");
                      }
                      assertEquals(
                          routeId,
                          activeSpan().getSpanId(),
                          "a sibling must not replace this branch context");
                    });
              }
            }
          });
      context.start();
      try (ProducerTemplate producer = context.createProducerTemplate();
          ContextScope ignored = activateSpan(parent)) {
        Context caller = Context.current();
        Exchange result =
            producer.request("direct:fanout", exchange -> exchange.getIn().setBody("left,right"));
        assertNull(result.getException());
        assertEquals(
            0, branches.getCount(), "both branches must finish before the request returns");
        assertSame(parent, activeSpan());
        assertSame(caller, Context.current());
      }
    } finally {
      parent.finish();
    }
    assertSame(original, Context.current());
    blockUntilTracesMatch(traces -> traces.stream().flatMap(List::stream).count() >= 4);
    List<DDSpan> spans =
        writer.stream()
            .flatMap(List::stream)
            .filter(span -> "camel.route".contentEquals(span.getOperationName()))
            .collect(Collectors.toList());
    assertEquals(3, spans.size());
    assertEquals(3, spans.stream().map(DDSpan::getSpanId).distinct().count());
    assertEquals(
        outerBefore.get(), outerAfter.get(), "aggregation must restore the enclosing route");
    assertEquals(2, observed.size());
    if (parallel) {
      assertEquals(2, threads.values().stream().distinct().count());
    }
    for (DDSpan route : spans) {
      String name = route.getResourceName().toString();
      assertEquals(parent.getTraceId(), route.getTraceId());
      assertEquals(
          "fanout".equals(name) ? parent.getSpanId() : outerBefore.get(), route.getParentId());
      assertEquals(
          "fanout".equals(name) ? outerBefore.get() : observed.get(name).longValue(),
          route.getSpanId());
      assertFalse(route.isError());
      assertTrue(route.getDurationNano() > 0);
    }
  }
}

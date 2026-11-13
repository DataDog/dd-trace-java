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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import org.apache.camel.Endpoint;
import org.apache.camel.Exchange;
import org.apache.camel.ExchangePattern;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.Test;

class CamelTemplateTest extends AbstractInstrumentationTest {
  @Test
  void requestAndSendOverloadsAndFutureKeepTheirCallerParent() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    Context original = Context.current();
    AgentSpan parent = startSpan("test", "template-parent");
    try (DefaultCamelContext context = new DefaultCamelContext()) {
      context.addRoutes(
          new RouteBuilder() {
            @Override
            public void configure() {
              from("direct:template")
                  .routeId("template")
                  .process(exchange -> {
                    assertTrue(activeSpan() != null, "each template entry must activate its route");
                    if ("future".equals(exchange.getMessage().getBody())) {
                      entered.countDown();
                      assertTrue(release.await(10, SECONDS));
                    }
                    exchange
                        .getMessage()
                        .setBody("reply:" + exchange.getMessage().getBody(String.class));
                  });
            }
          });
      context.start();
      try (ProducerTemplate producer = context.createProducerTemplate();
          ContextScope ignored = activateSpan(parent)) {
        Context caller = Context.current();
        Endpoint endpoint = context.getEndpoint("direct:template");
        Future<String> future =
            producer.asyncRequestBody("direct:template", "future", String.class);
        try {
          assertTrue(entered.await(10, SECONDS));
          assertFalse(future.isDone(), "the future must await route completion");
          assertEquals("reply:body", producer.requestBody("direct:template", "body"));
          assertSame(caller, Context.current());
          Exchange requested =
              producer.request(endpoint, exchange -> exchange.getIn().setBody("request"));
          assertNull(requested.getException());
          assertEquals("reply:request", requested.getMessage().getBody());
          assertSame(caller, Context.current());
          Exchange input = new DefaultExchange(context, ExchangePattern.InOut);
          input.getIn().setBody("exchange");
          Exchange sent = producer.send(endpoint, input);
          assertSame(input, sent);
          assertNull(sent.getException());
          assertEquals("reply:exchange", sent.getMessage().getBody());
          assertSame(caller, Context.current());
        } finally {
          release.countDown();
        }
        assertEquals("reply:future", future.get(10, SECONDS));
        assertTrue(future.isDone());
        assertSame(parent, activeSpan());
        assertSame(caller, Context.current());
      }
    } finally {
      release.countDown();
      parent.finish();
    }
    assertSame(original, Context.current());
    blockUntilTracesMatch(traces -> traces.stream().flatMap(List::stream).count() >= 5);
    List<DDSpan> spans = writer.stream().flatMap(List::stream).collect(Collectors.toList());
    assertEquals(5, spans.size(), "one caller and exactly four completed route spans");
    for (DDSpan route : spans) {
      if (route.getSpanId() != parent.getSpanId()) {
        assertEquals("camel.route", route.getOperationName().toString());
        assertEquals("template", route.getResourceName().toString());
        assertEquals(parent.getTraceId(), route.getTraceId());
        assertEquals(parent.getSpanId(), route.getParentId());
        assertFalse(route.isError());
        assertTrue(route.getDurationNano() > 0);
      }
    }
  }
}

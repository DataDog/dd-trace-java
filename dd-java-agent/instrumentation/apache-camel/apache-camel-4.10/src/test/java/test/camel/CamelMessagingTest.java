package test.camel;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.context.Context;
import datadog.trace.core.DDSpan;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.activemq.broker.BrokerService;
import org.apache.activemq.broker.TransportConnector;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.jms.JmsComponent;
import org.apache.camel.component.jms.JmsMessage;
import org.apache.camel.impl.DefaultCamelContext;
import org.junit.jupiter.api.Test;

class CamelMessagingTest extends AbstractCamelTest {
  @Test
  void jmsTransportConnectsIndependentSendingAndReceivingExchanges() throws Exception {
    Context original = Context.current();
    CountDownLatch received = new CountDownLatch(1);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    AtomicReference<String> sendingExchange = new AtomicReference<>();
    AtomicReference<String> receivingExchange = new AtomicReference<>();
    AtomicReference<String> wireParent = new AtomicReference<>();
    BrokerService broker = new BrokerService();
    broker.setPersistent(false);
    broker.setUseJmx(false);
    TransportConnector connector = broker.addConnector("tcp://127.0.0.1:0");
    try {
      broker.start();
      assertTrue(broker.waitUntilStarted());
      ActiveMQConnectionFactory factory =
          new ActiveMQConnectionFactory(connector.getPublishableConnectString());
      try (DefaultCamelContext context = new DefaultCamelContext()) {
        context.addComponent("jms", JmsComponent.jmsComponentAutoAcknowledge(factory));
        context.addRoutes(
            new RouteBuilder() {
              @Override
              public void configure() {
                from("direct:send")
                    .routeId("jms-sending")
                    .process(exchange -> sendingExchange.set(exchange.getExchangeId()))
                    .to("jms:queue:camel-trace");
                from("jms:queue:camel-trace?receiveTimeout=100")
                    .routeId("jms-receiving")
                    .process(exchange -> {
                      try {
                        receivingExchange.set(exchange.getExchangeId());
                        assertEquals("payload", exchange.getMessage().getBody(String.class));
                        assertEquals(
                            "retained", exchange.getMessage().getHeader("applicationHeader"));
                        assertNotNull(activeSpan());
                        assertEquals("camel.route", activeSpan().getOperationName().toString());
                        JmsMessage message = (JmsMessage) exchange.getIn();
                        wireParent.set(
                            message
                                .getJmsMessage()
                                .getStringProperty("x__dash__datadog__dash__parent__dash__id"));
                      } catch (Throwable error) {
                        failure.set(error);
                      } finally {
                        received.countDown();
                      }
                    });
              }
            });
        context.start();
        try (ProducerTemplate producer = context.createProducerTemplate()) {
          assertNull(activeSpan());
          producer.sendBodyAndHeader("direct:send", "payload", "applicationHeader", "retained");
          assertTrue(
              received.await(TIMEOUT_SECONDS, SECONDS), "the broker must deliver the message");
          assertNull(failure.get(), "receiving route assertions must succeed");
          assertNull(activeSpan());
          // AUTO_ACKNOWLEDGE consumer spans finish on the next receive iteration.
          blockUntilTracesMatch(traces -> traces.stream().flatMap(List::stream).count() >= 4);
        }
      }
    } finally {
      broker.stop();
      broker.waitUntilStopped();
    }
    assertSame(original, Context.current());
    assertNotEquals(
        sendingExchange.get(), receivingExchange.get(), "only the JMS message crosses the broker");
    List<DDSpan> spans = writer.stream().flatMap(List::stream).collect(Collectors.toList());
    assertEquals(4, spans.size(), "two routes and their JMS producer and consumer spans");
    DDSpan sending = routeSpan(spans, "jms-sending");
    DDSpan receiving = routeSpan(spans, "jms-receiving");
    DDSpan producer = transport(spans, "producer");
    DDSpan consumer = transport(spans, "consumer");
    assertEquals(0, sending.getParentId());
    assertEquals(sending.getSpanId(), producer.getParentId());
    assertEquals(producer.getSpanId(), consumer.getParentId());
    assertEquals(consumer.getSpanId(), receiving.getParentId());
    assertEquals(Long.toUnsignedString(producer.getSpanId()), wireParent.get());
    for (DDSpan span : spans) {
      assertEquals(sending.getTraceId(), span.getTraceId());
      assertFalse(span.isError());
    }
    assertEquals(2, routeSpans(2).size());
  }

  private static DDSpan transport(List<DDSpan> spans, String kind) {
    List<DDSpan> matching =
        spans.stream()
            .filter(span -> "jms".equals(String.valueOf(span.getTag("component"))))
            .filter(span -> kind.equals(span.getTag("span.kind")))
            .collect(Collectors.toList());
    assertEquals(1, matching.size(), "exactly one JMS " + kind + " span");
    return matching.get(0);
  }
}

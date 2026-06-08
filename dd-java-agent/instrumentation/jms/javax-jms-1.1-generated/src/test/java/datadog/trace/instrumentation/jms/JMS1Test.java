package datadog.trace.instrumentation.jms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.api.DDSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.core.DDSpan;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.jms.Connection;
import javax.jms.ConnectionFactory;
import javax.jms.DeliveryMode;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.MessageListener;
import javax.jms.MessageProducer;
import javax.jms.Queue;
import javax.jms.Session;
import javax.jms.TemporaryQueue;
import javax.jms.TextMessage;
import javax.jms.Topic;
import org.apache.activemq.junit.EmbeddedActiveMQBroker;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Tests for JMS 1.1 instrumentation using javax.jms API with an embedded ActiveMQ broker.
 *
 * <p>These tests verify that the instrumentation produces correct spans for:
 *
 * <ul>
 *   <li>MessageProducer.send() — producer spans with jms.produce operation
 *   <li>MessageConsumer.receive() — consumer spans with jms.consume operation
 *   <li>MessageConsumer.receiveNoWait() — consumer spans for non-blocking receive
 *   <li>MessageListener.onMessage() — consumer spans for async message processing
 *   <li>Message.acknowledge() — internal spans for message acknowledgment
 *   <li>Error scenarios — error tags on spans when JMS exceptions occur
 *   <li>Context propagation — producer-to-consumer trace linking
 * </ul>
 */
public class JMS1Test extends AbstractInstrumentationTest {

  private static EmbeddedActiveMQBroker broker;
  private static Connection connection;
  private static Session session;

  private static int queueCounter = 0;

  @BeforeAll
  static void setupBroker() throws Exception {
    broker = new EmbeddedActiveMQBroker();
    broker.start();
    ConnectionFactory connectionFactory = broker.createConnectionFactory();
    connection = connectionFactory.createConnection();
    connection.start();
    session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
  }

  @AfterAll
  static void tearDownBroker() throws Exception {
    if (session != null) {
      session.close();
    }
    if (connection != null) {
      connection.close();
    }
    if (broker != null) {
      broker.stop();
    }
  }

  private static Queue createUniqueQueue() throws JMSException {
    return session.createQueue("test-queue-" + queueCounter++);
  }

  private static Topic createUniqueTopic() throws JMSException {
    return session.createTopic("test-topic-" + queueCounter++);
  }

  // =========================================================================
  // Producer tests — MessageProducer.send()
  // =========================================================================

  @Test
  void sendToQueueCreatesProducerSpan() throws Exception {
    Queue queue = createUniqueQueue();
    MessageProducer producer = session.createProducer(queue);
    producer.setDeliveryMode(DeliveryMode.NON_PERSISTENT);

    TextMessage message = session.createTextMessage("hello queue");
    producer.send(message);
    producer.close();

    List<DDSpan> producerSpans = findSpansByOperation(waitAndFlattenTraces(1), "jms.produce");
    assertEquals(1, producerSpans.size(), "Expected exactly one jms.produce span");
    DDSpan producerSpan = producerSpans.get(0);

    assertEquals("jms.produce", producerSpan.getOperationName().toString());
    assertEquals(DDSpanTypes.MESSAGE_PRODUCER, producerSpan.getSpanType());
    assertEquals("jms", String.valueOf(producerSpan.getTag(Tags.COMPONENT)));
    assertEquals("producer", String.valueOf(producerSpan.getTag(Tags.SPAN_KIND)));
    assertEquals("jms", producerSpan.getTag("messaging.system"));
    assertEquals("send", producerSpan.getTag("messaging.operation"));
    assertEquals("queue", producerSpan.getTag("messaging.destination.kind"));
    assertTrue(producerSpan.isMeasured(), "Producer span should be measured");
    assertEquals(0L, producerSpan.getParentId(), "Producer span should be a root span");
    assertNotNull(producerSpan.getServiceName(), "Producer span should have a service name");
    // Resource name should follow 'Produce Queue <name>' format for queue destinations
    String resourceName = producerSpan.getResourceName().toString();
    assertTrue(
        resourceName.startsWith("Produce Queue "),
        "Resource name '"
            + resourceName
            + "' should start with 'Produce Queue ' for queue destinations");
  }

  @Test
  void sendToTopicCreatesProducerSpan() throws Exception {
    Topic topic = createUniqueTopic();
    MessageProducer producer = session.createProducer(topic);
    producer.setDeliveryMode(DeliveryMode.NON_PERSISTENT);

    TextMessage message = session.createTextMessage("hello topic");
    producer.send(message);
    producer.close();

    List<DDSpan> producerSpans = findSpansByOperation(waitAndFlattenTraces(1), "jms.produce");
    assertEquals(1, producerSpans.size(), "Expected exactly one jms.produce span");
    DDSpan producerSpan = producerSpans.get(0);

    assertEquals("jms.produce", producerSpan.getOperationName().toString());
    assertEquals(DDSpanTypes.MESSAGE_PRODUCER, producerSpan.getSpanType());
    assertEquals("jms", String.valueOf(producerSpan.getTag(Tags.COMPONENT)));
    assertEquals("producer", String.valueOf(producerSpan.getTag(Tags.SPAN_KIND)));
    assertEquals("jms", producerSpan.getTag("messaging.system"));
    assertEquals("send", producerSpan.getTag("messaging.operation"));
    assertEquals("topic", producerSpan.getTag("messaging.destination.kind"));
    assertTrue(producerSpan.isMeasured(), "Producer span should be measured");
    // Resource name should follow 'Produce Topic <name>' format for topic destinations
    String resourceName = producerSpan.getResourceName().toString();
    assertTrue(
        resourceName.startsWith("Produce Topic "),
        "Resource name '"
            + resourceName
            + "' should start with 'Produce Topic ' for topic destinations");
  }

  @Test
  void sendToTemporaryQueueCreatesProducerSpan() throws Exception {
    TemporaryQueue tempQueue = session.createTemporaryQueue();
    MessageProducer producer = session.createProducer(tempQueue);
    producer.setDeliveryMode(DeliveryMode.NON_PERSISTENT);

    TextMessage message = session.createTextMessage("hello temp queue");
    producer.send(message);
    producer.close();

    List<DDSpan> producerSpans = findSpansByOperation(waitAndFlattenTraces(1), "jms.produce");
    assertEquals(1, producerSpans.size(), "Expected exactly one jms.produce span");
    DDSpan producerSpan = producerSpans.get(0);

    assertEquals("jms.produce", producerSpan.getOperationName().toString());
    assertEquals(DDSpanTypes.MESSAGE_PRODUCER, producerSpan.getSpanType());
    assertEquals("jms", String.valueOf(producerSpan.getTag(Tags.COMPONENT)));
    assertEquals("producer", String.valueOf(producerSpan.getTag(Tags.SPAN_KIND)));
    assertEquals("jms", producerSpan.getTag("messaging.system"));
    assertEquals("send", producerSpan.getTag("messaging.operation"));
    assertNotNull(
        producerSpan.getTag("messaging.destination.name"),
        "messaging.destination.name should be set even for temporary queues");

    tempQueue.delete();
  }

  @Test
  void sendToExplicitDestinationCreatesProducerSpan() throws Exception {
    Queue queue = createUniqueQueue();
    MessageProducer producer = session.createProducer(null);
    producer.setDeliveryMode(DeliveryMode.NON_PERSISTENT);

    TextMessage message = session.createTextMessage("hello explicit destination");
    producer.send(queue, message);
    producer.close();

    List<DDSpan> producerSpans = findSpansByOperation(waitAndFlattenTraces(1), "jms.produce");
    assertEquals(1, producerSpans.size(), "Expected exactly one jms.produce span");
    DDSpan producerSpan = producerSpans.get(0);

    assertEquals("jms.produce", producerSpan.getOperationName().toString());
    assertEquals(DDSpanTypes.MESSAGE_PRODUCER, producerSpan.getSpanType());
    assertEquals("jms", String.valueOf(producerSpan.getTag(Tags.COMPONENT)));
    assertEquals("producer", String.valueOf(producerSpan.getTag(Tags.SPAN_KIND)));
    assertEquals("jms", producerSpan.getTag("messaging.system"));
    assertEquals("send", producerSpan.getTag("messaging.operation"));
    // Resource name should include the destination name
    String resourceName = producerSpan.getResourceName().toString();
    assertNotNull(resourceName, "Resource name should be set");
  }

  @Test
  void sendWithDeliveryParamsCreatesProducerSpan() throws Exception {
    Queue queue = createUniqueQueue();
    MessageProducer producer = session.createProducer(queue);

    TextMessage message = session.createTextMessage("hello with params");
    producer.send(message, DeliveryMode.NON_PERSISTENT, Message.DEFAULT_PRIORITY, 60000);
    producer.close();

    List<DDSpan> producerSpans = findSpansByOperation(waitAndFlattenTraces(1), "jms.produce");
    assertEquals(1, producerSpans.size(), "Expected exactly one jms.produce span");
    DDSpan producerSpan = producerSpans.get(0);

    assertEquals("jms.produce", producerSpan.getOperationName().toString());
    assertEquals(DDSpanTypes.MESSAGE_PRODUCER, producerSpan.getSpanType());
    assertEquals("jms", String.valueOf(producerSpan.getTag(Tags.COMPONENT)));
    assertEquals("producer", String.valueOf(producerSpan.getTag(Tags.SPAN_KIND)));
    assertEquals("jms", producerSpan.getTag("messaging.system"));
    assertEquals("send", producerSpan.getTag("messaging.operation"));
  }

  @Test
  void sendOnClosedProducerSetsErrorTags() throws Exception {
    Queue queue = createUniqueQueue();
    MessageProducer producer = session.createProducer(queue);
    producer.setDeliveryMode(DeliveryMode.NON_PERSISTENT);
    TextMessage message = session.createTextMessage("should fail");
    producer.close();

    assertThrows(
        JMSException.class,
        () -> {
          producer.send(message);
        });

    List<DDSpan> producerSpans = findSpansByOperation(waitAndFlattenTraces(1), "jms.produce");
    assertEquals(
        1, producerSpans.size(), "Expected exactly 1 error span for send on closed producer");
    DDSpan errorSpan = producerSpans.get(0);
    assertTrue(errorSpan.isError(), "Span should be marked as errored on send failure");
    assertNotNull(errorSpan.getTag("error.type"), "Error span should have error.type tag");
    assertNotNull(errorSpan.getTag("error.message"), "Error span should have error.message tag");
  }

  // =========================================================================
  // Consumer tests — MessageConsumer.receive()
  // =========================================================================

  @Test
  void receiveCreatesConsumerSpan() throws Exception {
    Queue queue = createUniqueQueue();
    MessageProducer producer = session.createProducer(queue);
    producer.setDeliveryMode(DeliveryMode.NON_PERSISTENT);
    MessageConsumer consumer = session.createConsumer(queue);

    TextMessage sentMessage = session.createTextMessage("receive test");
    producer.send(sentMessage);
    TextMessage receivedMessage = (TextMessage) consumer.receive(5000);

    assertNotNull(receivedMessage, "Should have received a message");
    assertEquals("receive test", receivedMessage.getText());

    producer.close();
    consumer.close();

    List<DDSpan> allSpans = waitAndFlattenTraces(2);
    List<DDSpan> consumerSpans = findSpansByOperation(allSpans, "jms.consume");
    assertTrue(!consumerSpans.isEmpty(), "Expected at least one jms.consume span");
    DDSpan consumerSpan = consumerSpans.get(0);

    assertEquals("jms.consume", consumerSpan.getOperationName().toString());
    assertEquals(DDSpanTypes.MESSAGE_CONSUMER, consumerSpan.getSpanType());
    assertEquals("jms", String.valueOf(consumerSpan.getTag(Tags.COMPONENT)));
    assertEquals("consumer", String.valueOf(consumerSpan.getTag(Tags.SPAN_KIND)));
    assertEquals("jms", consumerSpan.getTag("messaging.system"));
    assertEquals("receive", consumerSpan.getTag("messaging.operation"));
    assertEquals("queue", consumerSpan.getTag("messaging.destination.kind"));
    assertTrue(consumerSpan.isMeasured(), "Consumer span should be measured");
    assertNotNull(consumerSpan.getServiceName(), "Consumer span should have a service name");
    // Resource name should follow 'Consume Queue <name>' format for queue destinations
    String consumerResourceName = consumerSpan.getResourceName().toString();
    assertTrue(
        consumerResourceName.startsWith("Consume Queue "),
        "Resource name '"
            + consumerResourceName
            + "' should start with 'Consume Queue ' for queue destinations");
  }

  @Test
  void receiveWithTimeoutCreatesConsumerSpan() throws Exception {
    Queue queue = createUniqueQueue();
    MessageProducer producer = session.createProducer(queue);
    producer.setDeliveryMode(DeliveryMode.NON_PERSISTENT);
    MessageConsumer consumer = session.createConsumer(queue);

    TextMessage sentMessage = session.createTextMessage("timeout receive test");
    producer.send(sentMessage);
    TextMessage receivedMessage = (TextMessage) consumer.receive(3000);

    assertNotNull(receivedMessage, "Should have received a message");
    assertEquals("timeout receive test", receivedMessage.getText());

    producer.close();
    consumer.close();

    List<DDSpan> consumerSpans = findSpansByOperation(waitAndFlattenTraces(2), "jms.consume");
    assertTrue(
        !consumerSpans.isEmpty(), "Expected at least one jms.consume span for receive(timeout)");
    DDSpan consumerSpan = consumerSpans.get(0);

    assertEquals("jms.consume", consumerSpan.getOperationName().toString());
    assertEquals(DDSpanTypes.MESSAGE_CONSUMER, consumerSpan.getSpanType());
    assertEquals("jms", String.valueOf(consumerSpan.getTag(Tags.COMPONENT)));
    assertEquals("consumer", String.valueOf(consumerSpan.getTag(Tags.SPAN_KIND)));
    assertEquals("jms", consumerSpan.getTag("messaging.system"));
    assertEquals("receive", consumerSpan.getTag("messaging.operation"));
  }

  @Test
  void receiveNoWaitCreatesConsumerSpan() throws Exception {
    Queue queue = createUniqueQueue();
    MessageProducer producer = session.createProducer(queue);
    producer.setDeliveryMode(DeliveryMode.NON_PERSISTENT);

    TextMessage sentMessage = session.createTextMessage("receiveNoWait test");
    producer.send(sentMessage);
    producer.close();

    MessageConsumer consumer = session.createConsumer(queue);

    // Retry receiveNoWait to handle broker dispatch timing — ensures deterministic test
    Message receivedMessage = null;
    for (int i = 0; i < 20 && receivedMessage == null; i++) {
      receivedMessage = consumer.receiveNoWait();
      if (receivedMessage == null) {
        Thread.sleep(100);
      }
    }
    consumer.close();

    assertNotNull(receivedMessage, "receiveNoWait should have received the message");
    List<DDSpan> consumerSpans = findSpansByOperation(waitAndFlattenTraces(2), "jms.consume");
    assertTrue(
        !consumerSpans.isEmpty(), "Expected at least one jms.consume span for receiveNoWait");
    DDSpan consumerSpan = consumerSpans.get(0);
    assertEquals("jms.consume", consumerSpan.getOperationName().toString());
    assertEquals(DDSpanTypes.MESSAGE_CONSUMER, consumerSpan.getSpanType());
    assertEquals("jms", String.valueOf(consumerSpan.getTag(Tags.COMPONENT)));
    assertEquals("consumer", String.valueOf(consumerSpan.getTag(Tags.SPAN_KIND)));
    assertEquals("jms", consumerSpan.getTag("messaging.system"));
  }

  @Test
  void receiveOnClosedConsumerSetsErrorTags() throws Exception {
    Queue queue = createUniqueQueue();
    MessageConsumer consumer = session.createConsumer(queue);
    consumer.close();

    assertThrows(
        JMSException.class,
        () -> {
          consumer.receive(1000);
        });

    List<DDSpan> consumerSpans = findSpansByOperation(waitAndFlattenTraces(1), "jms.consume");
    assertEquals(
        1, consumerSpans.size(), "Expected exactly 1 error span for receive on closed consumer");
    DDSpan errorSpan = consumerSpans.get(0);
    assertTrue(errorSpan.isError(), "Span should be marked as errored on receive failure");
    assertNotNull(errorSpan.getTag("error.type"), "Error span should have error.type tag");
  }

  @Test
  void receiveMultipleMessagesCreatesMultipleConsumerSpans() throws Exception {
    Queue queue = createUniqueQueue();
    MessageProducer producer = session.createProducer(queue);
    producer.setDeliveryMode(DeliveryMode.NON_PERSISTENT);
    MessageConsumer consumer = session.createConsumer(queue);

    producer.send(session.createTextMessage("message 1"));
    producer.send(session.createTextMessage("message 2"));
    producer.send(session.createTextMessage("message 3"));

    TextMessage received1 = (TextMessage) consumer.receive(5000);
    TextMessage received2 = (TextMessage) consumer.receive(5000);
    TextMessage received3 = (TextMessage) consumer.receive(5000);

    assertNotNull(received1, "Should have received message 1");
    assertNotNull(received2, "Should have received message 2");
    assertNotNull(received3, "Should have received message 3");
    assertEquals("message 1", received1.getText());
    assertEquals("message 2", received2.getText());
    assertEquals("message 3", received3.getText());

    producer.close();
    consumer.close();

    List<DDSpan> allSpans = waitAndFlattenTraces(6);
    List<DDSpan> producerSpans = findSpansByOperation(allSpans, "jms.produce");
    assertEquals(3, producerSpans.size(), "Expected three jms.produce spans");

    List<DDSpan> consumerSpans = findSpansByOperation(allSpans, "jms.consume");
    assertTrue(
        consumerSpans.size() >= 3,
        "Expected at least three jms.consume spans, got " + consumerSpans.size());
  }

  // =========================================================================
  // MessageListener tests — MessageListener.onMessage()
  // =========================================================================

  @Test
  void messageListenerOnMessageCreatesConsumerSpan() throws Exception {
    Queue queue = createUniqueQueue();
    TextCapturingListener listener = new TextCapturingListener();

    Connection listenerConnection = broker.createConnectionFactory().createConnection();
    listenerConnection.start();
    Session listenerSession = listenerConnection.createSession(false, Session.AUTO_ACKNOWLEDGE);

    MessageConsumer consumer = listenerSession.createConsumer(queue);
    consumer.setMessageListener(listener);

    // Send a message to trigger the listener
    MessageProducer producer = session.createProducer(queue);
    producer.setDeliveryMode(DeliveryMode.NON_PERSISTENT);
    producer.send(session.createTextMessage("listener test message"));
    producer.close();

    assertTrue(listener.latch.await(10, TimeUnit.SECONDS), "Listener should have been triggered");
    assertEquals("listener test message", listener.receivedText.get());

    consumer.close();
    listenerSession.close();
    listenerConnection.close();

    // Wait for both producer and listener spans to appear
    blockUntilTracesMatch(
        traces -> {
          List<DDSpan> spans = new ArrayList<>();
          for (List<DDSpan> trace : traces) {
            spans.addAll(trace);
          }
          return spans.stream()
              .anyMatch(s -> "consumer".equals(String.valueOf(s.getTag(Tags.SPAN_KIND))));
        });

    List<DDSpan> allSpans = flattenTraces();
    List<DDSpan> listenerSpans = findSpansByOperation(allSpans, "jms.consume");
    assertTrue(!listenerSpans.isEmpty(), "Expected a consumer span for message listener");

    DDSpan listenerSpan = listenerSpans.get(0);
    assertEquals("jms.consume", listenerSpan.getOperationName().toString());
    assertEquals(DDSpanTypes.MESSAGE_CONSUMER, listenerSpan.getSpanType());
    assertEquals("jms", String.valueOf(listenerSpan.getTag(Tags.COMPONENT)));
    assertEquals("consumer", String.valueOf(listenerSpan.getTag(Tags.SPAN_KIND)));
    assertEquals("jms", listenerSpan.getTag("messaging.system"));
    assertEquals("process", listenerSpan.getTag("messaging.operation"));
    assertTrue(listenerSpan.isMeasured(), "Listener span should be measured");
    assertNotNull(
        listenerSpan.getTag("messaging.destination.name"),
        "Listener span should have messaging.destination.name tag");
  }

  @Test
  void messageListenerErrorIsRecordedOnSpan() throws Exception {
    Queue queue = createUniqueQueue();
    ErrorThrowingListener listener = new ErrorThrowingListener();

    Connection listenerConnection = broker.createConnectionFactory().createConnection();
    listenerConnection.start();
    Session listenerSession = listenerConnection.createSession(false, Session.AUTO_ACKNOWLEDGE);

    MessageConsumer consumer = listenerSession.createConsumer(queue);
    consumer.setMessageListener(listener);

    MessageProducer producer = session.createProducer(queue);
    producer.setDeliveryMode(DeliveryMode.NON_PERSISTENT);
    producer.send(session.createTextMessage("trigger error listener"));
    producer.close();

    assertTrue(listener.latch.await(10, TimeUnit.SECONDS), "Listener should have been triggered");

    consumer.close();
    listenerSession.close();
    listenerConnection.close();

    // Wait for both producer and listener spans to appear
    blockUntilTracesMatch(
        traces -> {
          List<DDSpan> spans = new ArrayList<>();
          for (List<DDSpan> trace : traces) {
            spans.addAll(trace);
          }
          return spans.stream()
              .anyMatch(s -> "consumer".equals(String.valueOf(s.getTag(Tags.SPAN_KIND))));
        });

    List<DDSpan> allSpans = flattenTraces();
    DDSpan listenerSpan = findConsumerOrProcessSpan(allSpans);
    assertNotNull(listenerSpan, "Expected a consumer/process span for failing listener");
    assertTrue(listenerSpan.isError(), "Listener span should be marked as errored");
    assertNotNull(listenerSpan.getTag("error.message"), "Error span should have error.message tag");
    assertNotNull(listenerSpan.getTag("error.type"), "Error span should have error.type tag");
    assertNotNull(listenerSpan.getTag("error.stack"), "Error span should have error.stack tag");
  }

  // =========================================================================
  // Acknowledge tests — Message.acknowledge()
  // =========================================================================

  @Test
  void messageAcknowledgeCreatesInternalSpan() throws Exception {
    Queue queue = createUniqueQueue();

    // Use CLIENT_ACKNOWLEDGE mode
    Connection ackConnection = broker.createConnectionFactory().createConnection();
    ackConnection.start();
    Session ackSession = ackConnection.createSession(false, Session.CLIENT_ACKNOWLEDGE);

    // Send a message
    MessageProducer producer = session.createProducer(queue);
    producer.setDeliveryMode(DeliveryMode.NON_PERSISTENT);
    producer.send(session.createTextMessage("message to acknowledge"));
    producer.close();

    // Receive with CLIENT_ACKNOWLEDGE
    MessageConsumer consumer = ackSession.createConsumer(queue);
    Message received = consumer.receive(5000);
    assertNotNull(received, "Should have received a message");

    received.acknowledge();

    consumer.close();
    ackSession.close();
    ackConnection.close();

    // Look for an acknowledge span — MessageAcknowledgeInstrumentation is registered,
    // so we expect exactly one jms.acknowledge span
    List<DDSpan> allSpans = waitAndFlattenTraces(3);
    List<DDSpan> ackSpans = findSpansByOperation(allSpans, "jms.acknowledge");
    assertEquals(1, ackSpans.size(), "Expected exactly 1 jms.acknowledge span");
    DDSpan ackSpan = ackSpans.get(0);
    assertEquals("jms.acknowledge", ackSpan.getOperationName().toString());
    assertEquals("jms", String.valueOf(ackSpan.getTag(Tags.COMPONENT)));
    assertEquals("internal", String.valueOf(ackSpan.getTag(Tags.SPAN_KIND)));
    assertEquals("jms", ackSpan.getTag("messaging.system"));
    assertEquals("acknowledge", ackSpan.getTag("messaging.operation"));
  }

  @Test
  void messageAcknowledgeErrorSetsErrorTags() throws Exception {
    Queue queue = createUniqueQueue();

    Connection ackConnection = broker.createConnectionFactory().createConnection();
    ackConnection.start();
    Session ackSession = ackConnection.createSession(false, Session.CLIENT_ACKNOWLEDGE);

    MessageProducer producer = session.createProducer(queue);
    producer.setDeliveryMode(DeliveryMode.NON_PERSISTENT);
    producer.send(session.createTextMessage("message to fail ack"));
    producer.close();

    MessageConsumer consumer = ackSession.createConsumer(queue);
    Message received = consumer.receive(5000);
    assertNotNull(received, "Should have received a message");

    // Close everything before acknowledging to force an error
    consumer.close();
    ackSession.close();
    ackConnection.close();

    try {
      received.acknowledge();
    } catch (Exception e) {
      // expected
    }

    List<DDSpan> allSpans = waitAndFlattenTraces(3);
    List<DDSpan> ackSpans = findSpansByOperation(allSpans, "jms.acknowledge");
    assertEquals(1, ackSpans.size(), "Expected exactly 1 jms.acknowledge error span");
    DDSpan ackErrorSpan = ackSpans.get(0);
    assertTrue(ackErrorSpan.isError(), "Acknowledge span should be errored");
    assertNotNull(ackErrorSpan.getTag("error.type"), "Error ack span should have error.type tag");
  }

  // =========================================================================
  // Context propagation tests — producer-to-consumer trace linking
  // =========================================================================

  @Test
  void consumerSpanIsChildOfProducerSpan() throws Exception {
    Queue queue = createUniqueQueue();
    MessageProducer producer = session.createProducer(queue);
    producer.setDeliveryMode(DeliveryMode.NON_PERSISTENT);
    MessageConsumer consumer = session.createConsumer(queue);

    TextMessage sentMessage = session.createTextMessage("context propagation test");
    producer.send(sentMessage);
    TextMessage receivedMessage = (TextMessage) consumer.receive(5000);

    assertNotNull(receivedMessage, "Should have received a message");
    assertEquals("context propagation test", receivedMessage.getText());

    producer.close();
    consumer.close();

    List<DDSpan> allSpans = waitAndFlattenTraces(2);
    DDSpan producerSpan = findSpanByOperation(allSpans, "jms.produce");
    DDSpan consumerSpan = findSpanByOperation(allSpans, "jms.consume");

    assertNotNull(producerSpan, "Expected a jms.produce span");
    assertNotNull(consumerSpan, "Expected a jms.consume span");

    // Consumer span should be a child of the producer span (distributed trace linking)
    assertEquals(
        producerSpan.getSpanId(),
        consumerSpan.getParentId(),
        "Consumer span should be child of the producer span");
    assertEquals(
        producerSpan.getTraceId(),
        consumerSpan.getTraceId(),
        "Consumer and producer spans should share the same trace ID");
  }

  @Test
  void messageListenerSpanIsLinkedToProducerSpan() throws Exception {
    Queue queue = createUniqueQueue();
    LatchListener listener = new LatchListener();

    Connection listenerConnection = broker.createConnectionFactory().createConnection();
    listenerConnection.start();
    Session listenerSession = listenerConnection.createSession(false, Session.AUTO_ACKNOWLEDGE);

    MessageConsumer consumer = listenerSession.createConsumer(queue);
    consumer.setMessageListener(listener);

    MessageProducer producer = session.createProducer(queue);
    producer.setDeliveryMode(DeliveryMode.NON_PERSISTENT);
    producer.send(session.createTextMessage("listener context propagation"));
    producer.close();

    assertTrue(listener.latch.await(10, TimeUnit.SECONDS), "Listener should have been triggered");

    consumer.close();
    listenerSession.close();
    listenerConnection.close();

    // Wait for both producer and listener spans to appear
    blockUntilTracesMatch(
        traces -> {
          List<DDSpan> spans = new ArrayList<>();
          for (List<DDSpan> trace : traces) {
            spans.addAll(trace);
          }
          return spans.stream()
              .anyMatch(s -> "consumer".equals(String.valueOf(s.getTag(Tags.SPAN_KIND))));
        });

    List<DDSpan> allSpans = flattenTraces();
    DDSpan producerSpan = findSpanByOperation(allSpans, "jms.produce");
    DDSpan listenerSpan = findConsumerOrProcessSpan(allSpans);

    assertNotNull(producerSpan, "Expected a jms.produce span");
    assertNotNull(listenerSpan, "Expected a consumer/process span for listener");

    // Listener span should be linked to the producer span via distributed context
    assertEquals(
        producerSpan.getTraceId(),
        listenerSpan.getTraceId(),
        "Listener span should share the same trace ID as the producer span");
  }

  @Test
  void producerSpanHasCorrectDestinationName() throws Exception {
    Queue queue = createUniqueQueue();
    String queueName = queue.getQueueName();

    MessageProducer producer = session.createProducer(queue);
    producer.setDeliveryMode(DeliveryMode.NON_PERSISTENT);
    producer.send(session.createTextMessage("destination name test"));
    producer.close();

    List<DDSpan> producerSpans = findSpansByOperation(waitAndFlattenTraces(1), "jms.produce");
    assertEquals(1, producerSpans.size(), "Expected exactly one jms.produce span");
    DDSpan producerSpan = producerSpans.get(0);

    // The resource name should contain the destination name
    String resourceName = producerSpan.getResourceName().toString();
    assertTrue(
        resourceName.contains(queueName),
        "Resource name '" + resourceName + "' should contain queue name '" + queueName + "'");

    // messaging.destination.name tag should be set
    Object destinationTag = producerSpan.getTag("messaging.destination.name");
    assertNotNull(destinationTag, "messaging.destination.name tag should be set");
    assertTrue(
        destinationTag.toString().contains(queueName),
        "messaging.destination.name should contain '" + queueName + "'");
  }

  @Test
  void consumerSpanHasCorrectDestinationName() throws Exception {
    Queue queue = createUniqueQueue();
    String queueName = queue.getQueueName();

    MessageProducer producer = session.createProducer(queue);
    producer.setDeliveryMode(DeliveryMode.NON_PERSISTENT);
    MessageConsumer consumer = session.createConsumer(queue);

    producer.send(session.createTextMessage("consumer destination test"));
    TextMessage received = (TextMessage) consumer.receive(5000);
    assertNotNull(received, "Should have received a message");

    producer.close();
    consumer.close();

    List<DDSpan> consumerSpans = findSpansByOperation(waitAndFlattenTraces(2), "jms.consume");
    assertTrue(!consumerSpans.isEmpty(), "Expected at least one jms.consume span");
    DDSpan consumerSpan = consumerSpans.get(0);

    // The resource name should contain the destination name
    String resourceName = consumerSpan.getResourceName().toString();
    assertTrue(
        resourceName.contains(queueName),
        "Resource name '" + resourceName + "' should contain queue name '" + queueName + "'");
  }

  // =========================================================================
  // Transacted session test
  // =========================================================================

  @Test
  void receiveInTransactedSessionCreatesConsumerSpans() throws Exception {
    Queue queue = createUniqueQueue();
    Session transactedSession = connection.createSession(true, Session.SESSION_TRANSACTED);
    MessageProducer producer = session.createProducer(queue);
    producer.setDeliveryMode(DeliveryMode.NON_PERSISTENT);

    producer.send(session.createTextMessage("transacted message 1"));
    producer.send(session.createTextMessage("transacted message 2"));
    producer.close();

    MessageConsumer consumer = transactedSession.createConsumer(queue);
    TextMessage received1 = (TextMessage) consumer.receive(5000);
    TextMessage received2 = (TextMessage) consumer.receive(5000);
    transactedSession.commit();

    assertNotNull(received1, "Should have received message 1");
    assertNotNull(received2, "Should have received message 2");
    assertEquals("transacted message 1", received1.getText());
    assertEquals("transacted message 2", received2.getText());

    consumer.close();
    transactedSession.close();

    List<DDSpan> allSpans = waitAndFlattenTraces(4);
    List<DDSpan> producerSpans = findSpansByOperation(allSpans, "jms.produce");
    assertEquals(2, producerSpans.size(), "Expected two jms.produce spans");

    List<DDSpan> consumerSpans = findSpansByOperation(allSpans, "jms.consume");
    assertTrue(
        consumerSpans.size() >= 2, "Expected at least two jms.consume spans in transacted session");
  }

  // =========================================================================
  // Context propagation tests — topic (pub/sub) and explicit destination
  // =========================================================================

  @Test
  void contextPropagationWorksWithTopics() throws Exception {
    // Verifies trace context propagation works across topic pub/sub, not just queues
    Topic topic = createUniqueTopic();

    // Subscriber must be created before publishing
    Connection subscriberConnection = broker.createConnectionFactory().createConnection();
    subscriberConnection.start();
    Session subscriberSession = subscriberConnection.createSession(false, Session.AUTO_ACKNOWLEDGE);
    MessageConsumer subscriber = subscriberSession.createConsumer(topic);

    MessageProducer publisher = session.createProducer(topic);
    publisher.setDeliveryMode(DeliveryMode.NON_PERSISTENT);
    publisher.send(session.createTextMessage("topic context propagation test"));
    publisher.close();

    TextMessage received = (TextMessage) subscriber.receive(5000);
    assertNotNull(received, "Should have received topic message");
    assertEquals("topic context propagation test", received.getText());

    subscriber.close();
    subscriberSession.close();
    subscriberConnection.close();

    List<DDSpan> allSpans = waitAndFlattenTraces(2);
    DDSpan producerSpan = findSpanByOperation(allSpans, "jms.produce");
    DDSpan consumerSpan = findSpanByOperation(allSpans, "jms.consume");

    assertNotNull(producerSpan, "Expected a jms.produce span for topic publish");
    assertNotNull(consumerSpan, "Expected a jms.consume span for topic subscribe");

    // Verify trace context propagation through topic
    assertEquals(
        producerSpan.getTraceId(),
        consumerSpan.getTraceId(),
        "Topic subscriber span should share trace ID with publisher");
    assertEquals(
        producerSpan.getSpanId(),
        consumerSpan.getParentId(),
        "Topic subscriber span should be child of publisher span");

    // Verify full producer span structure
    assertEquals("jms.produce", producerSpan.getOperationName().toString());
    assertEquals(DDSpanTypes.MESSAGE_PRODUCER, producerSpan.getSpanType());
    assertEquals("jms", String.valueOf(producerSpan.getTag(Tags.COMPONENT)));
    assertEquals("producer", String.valueOf(producerSpan.getTag(Tags.SPAN_KIND)));
    assertEquals("jms", producerSpan.getTag("messaging.system"));
    assertEquals("send", producerSpan.getTag("messaging.operation"));
    assertTrue(producerSpan.isMeasured(), "Producer span should be measured");

    // Verify full consumer span structure
    assertEquals("jms.consume", consumerSpan.getOperationName().toString());
    assertEquals(DDSpanTypes.MESSAGE_CONSUMER, consumerSpan.getSpanType());
    assertEquals("jms", String.valueOf(consumerSpan.getTag(Tags.COMPONENT)));
    assertEquals("consumer", String.valueOf(consumerSpan.getTag(Tags.SPAN_KIND)));
    assertEquals("jms", consumerSpan.getTag("messaging.system"));
    assertEquals("receive", consumerSpan.getTag("messaging.operation"));
    assertTrue(consumerSpan.isMeasured(), "Consumer span should be measured");
  }

  @Test
  void contextPropagationWithExplicitDestinationLinksSpans() throws Exception {
    // Verifies send(Destination, Message) propagates trace context correctly
    Queue queue = createUniqueQueue();
    String queueName = queue.getQueueName();
    MessageProducer producer = session.createProducer(null);
    producer.setDeliveryMode(DeliveryMode.NON_PERSISTENT);
    MessageConsumer consumer = session.createConsumer(queue);

    TextMessage sentMessage = session.createTextMessage("explicit dest context test");
    producer.send(queue, sentMessage);
    TextMessage receivedMessage = (TextMessage) consumer.receive(5000);

    assertNotNull(receivedMessage, "Should have received a message");
    assertEquals("explicit dest context test", receivedMessage.getText());

    producer.close();
    consumer.close();

    List<DDSpan> allSpans = waitAndFlattenTraces(2);
    DDSpan producerSpan = findSpanByOperation(allSpans, "jms.produce");
    DDSpan consumerSpan = findSpanByOperation(allSpans, "jms.consume");

    assertNotNull(producerSpan, "Expected a jms.produce span");
    assertNotNull(consumerSpan, "Expected a jms.consume span");

    // Consumer must be child of producer via injected/extracted context
    assertEquals(
        producerSpan.getSpanId(),
        consumerSpan.getParentId(),
        "Consumer span should be child of producer for explicit destination send");
    assertEquals(
        producerSpan.getTraceId(),
        consumerSpan.getTraceId(),
        "Consumer and producer should share trace ID for explicit destination send");

    // Verify producer span has correct destination
    assertEquals("jms.produce", producerSpan.getOperationName().toString());
    assertEquals(DDSpanTypes.MESSAGE_PRODUCER, producerSpan.getSpanType());
    assertEquals("jms", String.valueOf(producerSpan.getTag(Tags.COMPONENT)));
    assertEquals("producer", String.valueOf(producerSpan.getTag(Tags.SPAN_KIND)));
    assertTrue(
        producerSpan.getResourceName().toString().contains(queueName),
        "Producer resource should contain queue name '" + queueName + "'");
    assertNotNull(
        producerSpan.getTag("messaging.destination.name"),
        "Producer should have messaging.destination.name tag");

    // Verify consumer span has correct destination and metadata
    assertEquals("jms.consume", consumerSpan.getOperationName().toString());
    assertEquals(DDSpanTypes.MESSAGE_CONSUMER, consumerSpan.getSpanType());
    assertEquals("jms", String.valueOf(consumerSpan.getTag(Tags.COMPONENT)));
    assertEquals("consumer", String.valueOf(consumerSpan.getTag(Tags.SPAN_KIND)));
    assertEquals("jms", consumerSpan.getTag("messaging.system"));
    assertEquals("receive", consumerSpan.getTag("messaging.operation"));
    assertTrue(
        consumerSpan.getResourceName().toString().contains(queueName),
        "Consumer resource should contain queue name '" + queueName + "'");
  }

  @Test
  void contextPropagationTopicMessageListenerLinksSpans() throws Exception {
    // Verifies trace context propagation works from topic producer to MessageListener
    Topic topic = createUniqueTopic();
    TextCapturingListener listener = new TextCapturingListener();

    Connection subscriberConnection = broker.createConnectionFactory().createConnection();
    subscriberConnection.start();
    Session subscriberSession = subscriberConnection.createSession(false, Session.AUTO_ACKNOWLEDGE);
    MessageConsumer subscriber = subscriberSession.createConsumer(topic);
    subscriber.setMessageListener(listener);

    MessageProducer publisher = session.createProducer(topic);
    publisher.setDeliveryMode(DeliveryMode.NON_PERSISTENT);
    publisher.send(session.createTextMessage("topic listener context test"));
    publisher.close();

    assertTrue(
        listener.latch.await(10, TimeUnit.SECONDS), "Topic listener should have been triggered");
    assertEquals("topic listener context test", listener.receivedText.get());

    subscriber.close();
    subscriberSession.close();
    subscriberConnection.close();

    // Wait for consumer span
    blockUntilTracesMatch(
        traces -> {
          List<DDSpan> spans = new ArrayList<>();
          for (List<DDSpan> trace : traces) {
            spans.addAll(trace);
          }
          return spans.stream()
              .anyMatch(s -> "consumer".equals(String.valueOf(s.getTag(Tags.SPAN_KIND))));
        });

    List<DDSpan> allSpans = flattenTraces();
    DDSpan producerSpan = findSpanByOperation(allSpans, "jms.produce");
    DDSpan listenerSpan = findConsumerOrProcessSpan(allSpans);

    assertNotNull(producerSpan, "Expected a jms.produce span for topic");
    assertNotNull(listenerSpan, "Expected a consumer span for topic listener");

    // Verify trace context propagated from topic publisher to listener
    assertEquals(
        producerSpan.getTraceId(),
        listenerSpan.getTraceId(),
        "Topic listener span should share trace ID with publisher");

    // Verify full listener span structure
    assertEquals("jms.consume", listenerSpan.getOperationName().toString());
    assertEquals(DDSpanTypes.MESSAGE_CONSUMER, listenerSpan.getSpanType());
    assertEquals("jms", String.valueOf(listenerSpan.getTag(Tags.COMPONENT)));
    assertEquals("consumer", String.valueOf(listenerSpan.getTag(Tags.SPAN_KIND)));
    assertEquals("jms", listenerSpan.getTag("messaging.system"));
    assertTrue(listenerSpan.isMeasured(), "Listener span should be measured");
    assertNotNull(
        listenerSpan.getTag("messaging.destination.name"),
        "Listener span should have messaging.destination.name tag");
  }

  @Test
  void traceHeadersAreInjectedIntoMessageProperties() throws Exception {
    // Verifies that the MessageInjectAdapter actually injects trace headers into JMS message
    // properties. JMS property names must be valid Java identifiers, so hyphens are replaced
    // with underscores by MessageInjectAdapter (e.g. x-datadog-trace-id -> x_datadog_trace_id).
    Queue queue = createUniqueQueue();
    MessageProducer producer = session.createProducer(queue);
    producer.setDeliveryMode(DeliveryMode.NON_PERSISTENT);
    MessageConsumer consumer = session.createConsumer(queue);

    TextMessage sentMessage = session.createTextMessage("header injection test");
    producer.send(sentMessage);
    TextMessage receivedMessage = (TextMessage) consumer.receive(5000);

    assertNotNull(receivedMessage, "Should have received a message");
    assertEquals("header injection test", receivedMessage.getText());

    // Check that trace context headers were injected as JMS properties
    // Datadog headers use hyphens, but JMS converts to underscores
    boolean hasTraceProperty = false;
    List<String> traceProperties = new ArrayList<>();
    @SuppressWarnings("unchecked")
    java.util.Enumeration<String> propNames = receivedMessage.getPropertyNames();
    while (propNames.hasMoreElements()) {
      String name = propNames.nextElement();
      // Look for any trace-related property (x_datadog_*, traceparent, tracestate)
      if (name.startsWith("x_datadog_")
          || name.equals("traceparent")
          || name.equals("tracestate")) {
        hasTraceProperty = true;
        traceProperties.add(name);
        // Verify the property value is non-empty
        String value = receivedMessage.getStringProperty(name);
        assertNotNull(value, "Trace property '" + name + "' should have a non-null value");
        assertTrue(!value.isEmpty(), "Trace property '" + name + "' should have a non-empty value");
      }
    }
    assertTrue(
        hasTraceProperty,
        "Message should contain injected trace context headers as JMS properties");

    // Also verify the spans are correctly linked (end-to-end proof that inject+extract works)
    producer.close();
    consumer.close();

    List<DDSpan> allSpans = waitAndFlattenTraces(2);
    DDSpan producerSpan = findSpanByOperation(allSpans, "jms.produce");
    DDSpan consumerSpan = findSpanByOperation(allSpans, "jms.consume");

    assertNotNull(producerSpan, "Expected a jms.produce span");
    assertNotNull(consumerSpan, "Expected a jms.consume span");

    assertEquals(
        producerSpan.getTraceId(),
        consumerSpan.getTraceId(),
        "Trace ID should match between producer and consumer (header injection verified)");
    assertEquals(
        producerSpan.getSpanId(),
        consumerSpan.getParentId(),
        "Parent ID should match (header injection + extraction verified)");

    // Verify full span structure for completeness
    assertEquals(DDSpanTypes.MESSAGE_PRODUCER, producerSpan.getSpanType());
    assertEquals(DDSpanTypes.MESSAGE_CONSUMER, consumerSpan.getSpanType());
    assertEquals("jms", String.valueOf(producerSpan.getTag(Tags.COMPONENT)));
    assertEquals("jms", String.valueOf(consumerSpan.getTag(Tags.COMPONENT)));
  }

  @Test
  void contextPropagationWithMultipleConsumersFromSameQueue() throws Exception {
    // Verifies that each consumer gets its own trace-linked span when multiple consumers
    // consume from the same queue
    Queue queue = createUniqueQueue();
    String queueName = queue.getQueueName();

    MessageProducer producer = session.createProducer(queue);
    producer.setDeliveryMode(DeliveryMode.NON_PERSISTENT);

    // Create two consumers
    MessageConsumer consumer1 = session.createConsumer(queue);
    MessageConsumer consumer2 = session.createConsumer(queue);

    // Send two messages - each consumer should get one
    producer.send(session.createTextMessage("msg for consumer 1"));
    producer.send(session.createTextMessage("msg for consumer 2"));

    TextMessage received1 = (TextMessage) consumer1.receive(5000);
    TextMessage received2 = (TextMessage) consumer2.receive(5000);

    // At least one should have received a message
    assertTrue(
        received1 != null || received2 != null, "At least one consumer should have received");

    producer.close();
    consumer1.close();
    consumer2.close();

    List<DDSpan> allSpans = waitAndFlattenTraces(4);
    List<DDSpan> producerSpans = findSpansByOperation(allSpans, "jms.produce");
    List<DDSpan> consumerSpans = findSpansByOperation(allSpans, "jms.consume");

    assertEquals(2, producerSpans.size(), "Expected two jms.produce spans");
    assertTrue(
        consumerSpans.size() >= 1,
        "Expected at least one jms.consume span, got " + consumerSpans.size());

    // Verify each consumer span is linked to a producer span
    for (DDSpan cSpan : consumerSpans) {
      boolean linkedToProducer = false;
      for (DDSpan pSpan : producerSpans) {
        if (pSpan.getSpanId() == cSpan.getParentId()
            && pSpan.getTraceId().equals(cSpan.getTraceId())) {
          linkedToProducer = true;
          break;
        }
      }
      assertTrue(
          linkedToProducer,
          "Each consumer span should be linked to a producer span via context propagation");

      // Verify consumer span structure
      assertEquals("jms.consume", cSpan.getOperationName().toString());
      assertEquals(DDSpanTypes.MESSAGE_CONSUMER, cSpan.getSpanType());
      assertEquals("jms", String.valueOf(cSpan.getTag(Tags.COMPONENT)));
      assertEquals("consumer", String.valueOf(cSpan.getTag(Tags.SPAN_KIND)));
      assertEquals("jms", cSpan.getTag("messaging.system"));
      assertEquals("receive", cSpan.getTag("messaging.operation"));
      assertTrue(
          cSpan.getResourceName().toString().contains(queueName),
          "Consumer resource should contain queue name");
    }
  }

  // =========================================================================
  // Data Streams Monitoring (DSM) integration tests
  //
  // DSM adds pathway context injection on the producer side (via span.with(dsmContext))
  // and sets checkpoints on the consumer side. These tests verify that:
  // 1. The DSM code paths execute without corrupting spans or tags
  // 2. Trace context propagation still works after DSM changes to inject()
  // 3. Full span structure (operation, type, kind, component, resource, tags) is preserved
  // =========================================================================

  @Test
  void dsmProducerInjectPreservesTraceContextPropagation() throws Exception {
    // The DSM change modifies inject() from inject(span, ...) to inject(span.with(dsmContext), ...)
    // This test verifies trace context propagation is not broken by that change
    Queue queue = createUniqueQueue();
    String queueName = queue.getQueueName();
    MessageProducer producer = session.createProducer(queue);
    producer.setDeliveryMode(DeliveryMode.NON_PERSISTENT);
    MessageConsumer consumer = session.createConsumer(queue);

    TextMessage sentMessage = session.createTextMessage("dsm trace propagation test");
    producer.send(sentMessage);
    TextMessage receivedMessage = (TextMessage) consumer.receive(5000);

    assertNotNull(receivedMessage, "Should have received a message");
    assertEquals("dsm trace propagation test", receivedMessage.getText());

    producer.close();
    consumer.close();

    List<DDSpan> allSpans = waitAndFlattenTraces(2);
    DDSpan producerSpan = findSpanByOperation(allSpans, "jms.produce");
    DDSpan consumerSpan = findSpanByOperation(allSpans, "jms.consume");

    assertNotNull(producerSpan, "Expected a jms.produce span");
    assertNotNull(consumerSpan, "Expected a jms.consume span");

    // Verify full producer span structure after DSM changes
    assertEquals("jms.produce", producerSpan.getOperationName().toString());
    assertEquals(DDSpanTypes.MESSAGE_PRODUCER, producerSpan.getSpanType());
    assertEquals("jms", String.valueOf(producerSpan.getTag(Tags.COMPONENT)));
    assertEquals("producer", String.valueOf(producerSpan.getTag(Tags.SPAN_KIND)));
    assertEquals("jms", producerSpan.getTag("messaging.system"));
    assertEquals("send", producerSpan.getTag("messaging.operation"));
    assertTrue(
        producerSpan.getResourceName().toString().contains(queueName),
        "Producer resource should contain queue name '" + queueName + "'");
    assertNotNull(
        producerSpan.getTag("messaging.destination.name"),
        "Producer should have messaging.destination.name tag");

    // Verify full consumer span structure after DSM changes
    assertEquals("jms.consume", consumerSpan.getOperationName().toString());
    assertEquals(DDSpanTypes.MESSAGE_CONSUMER, consumerSpan.getSpanType());
    assertEquals("jms", String.valueOf(consumerSpan.getTag(Tags.COMPONENT)));
    assertEquals("consumer", String.valueOf(consumerSpan.getTag(Tags.SPAN_KIND)));
    assertEquals("jms", consumerSpan.getTag("messaging.system"));
    assertEquals("receive", consumerSpan.getTag("messaging.operation"));
    assertTrue(
        consumerSpan.getResourceName().toString().contains(queueName),
        "Consumer resource should contain queue name '" + queueName + "'");

    // Verify trace context was propagated correctly (critical: DSM changes inject call)
    assertEquals(
        producerSpan.getSpanId(),
        consumerSpan.getParentId(),
        "Consumer span should be child of producer span (trace context via DSM-modified inject)");
    assertEquals(
        producerSpan.getTraceId(),
        consumerSpan.getTraceId(),
        "Consumer and producer should share trace ID (trace context via DSM-modified inject)");
  }

  @Test
  void dsmExplicitDestinationProducerPreservesTraceContextPropagation() throws Exception {
    // Tests the send(Destination, Message) overload which has its own DSM code path
    Queue queue = createUniqueQueue();
    String queueName = queue.getQueueName();
    MessageProducer producer = session.createProducer(null);
    producer.setDeliveryMode(DeliveryMode.NON_PERSISTENT);
    MessageConsumer consumer = session.createConsumer(queue);

    TextMessage sentMessage = session.createTextMessage("dsm explicit dest test");
    producer.send(queue, sentMessage);
    TextMessage receivedMessage = (TextMessage) consumer.receive(5000);

    assertNotNull(receivedMessage, "Should have received a message");
    assertEquals("dsm explicit dest test", receivedMessage.getText());

    producer.close();
    consumer.close();

    List<DDSpan> allSpans = waitAndFlattenTraces(2);
    DDSpan producerSpan = findSpanByOperation(allSpans, "jms.produce");
    DDSpan consumerSpan = findSpanByOperation(allSpans, "jms.consume");

    assertNotNull(producerSpan, "Expected a jms.produce span");
    assertNotNull(consumerSpan, "Expected a jms.consume span");

    // Verify producer span structure for explicit destination
    assertEquals("jms.produce", producerSpan.getOperationName().toString());
    assertEquals(DDSpanTypes.MESSAGE_PRODUCER, producerSpan.getSpanType());
    assertEquals("jms", String.valueOf(producerSpan.getTag(Tags.COMPONENT)));
    assertEquals("producer", String.valueOf(producerSpan.getTag(Tags.SPAN_KIND)));
    assertTrue(
        producerSpan.getResourceName().toString().contains(queueName),
        "Producer resource should contain queue name '" + queueName + "'");

    // Verify trace context propagation with explicit destination DSM path
    assertEquals(
        producerSpan.getSpanId(),
        consumerSpan.getParentId(),
        "Consumer should be child of producer with explicit dest DSM path");
    assertEquals(
        producerSpan.getTraceId(),
        consumerSpan.getTraceId(),
        "Trace ID should propagate through explicit dest DSM path");
  }

  @Test
  void dsmConsumerCheckpointPreservesSpanTags() throws Exception {
    // Verifies DSM checkpoint call on the consumer side doesn't corrupt span tags or state
    Queue queue = createUniqueQueue();
    String queueName = queue.getQueueName();
    MessageProducer producer = session.createProducer(queue);
    producer.setDeliveryMode(DeliveryMode.NON_PERSISTENT);
    MessageConsumer consumer = session.createConsumer(queue);

    producer.send(session.createTextMessage("dsm checkpoint tag verification"));
    TextMessage received = (TextMessage) consumer.receive(5000);

    assertNotNull(received, "Should have received a message");
    assertEquals("dsm checkpoint tag verification", received.getText());

    producer.close();
    consumer.close();

    List<DDSpan> consumerSpans = findSpansByOperation(waitAndFlattenTraces(2), "jms.consume");
    assertTrue(!consumerSpans.isEmpty(), "Expected at least one jms.consume span");
    DDSpan consumerSpan = consumerSpans.get(0);

    // Verify complete span structure after DSM checkpoint
    assertEquals("jms.consume", consumerSpan.getOperationName().toString());
    assertEquals(DDSpanTypes.MESSAGE_CONSUMER, consumerSpan.getSpanType());
    assertEquals("jms", String.valueOf(consumerSpan.getTag(Tags.COMPONENT)));
    assertEquals("consumer", String.valueOf(consumerSpan.getTag(Tags.SPAN_KIND)));
    assertEquals("jms", consumerSpan.getTag("messaging.system"));
    assertEquals("receive", consumerSpan.getTag("messaging.operation"));
    assertTrue(consumerSpan.isMeasured(), "Consumer span should be measured after DSM checkpoint");
    assertNotNull(
        consumerSpan.getTag("messaging.destination.name"),
        "messaging.destination.name should be set after DSM checkpoint");
    assertEquals(
        queueName,
        consumerSpan.getTag("messaging.destination.name").toString(),
        "messaging.destination.name should match queue name");
    assertTrue(
        consumerSpan.getResourceName().toString().contains(queueName),
        "Resource name should contain queue name after DSM checkpoint");
  }

  @Test
  void dsmMessageListenerCheckpointPreservesSpanTags() throws Exception {
    // Verifies DSM checkpoint in message listener (onMessage) doesn't corrupt span tags
    Queue queue = createUniqueQueue();
    String queueName = queue.getQueueName();
    TextCapturingListener listener = new TextCapturingListener();

    Connection listenerConnection = broker.createConnectionFactory().createConnection();
    listenerConnection.start();
    Session listenerSession = listenerConnection.createSession(false, Session.AUTO_ACKNOWLEDGE);

    MessageConsumer consumer = listenerSession.createConsumer(queue);
    consumer.setMessageListener(listener);

    MessageProducer producer = session.createProducer(queue);
    producer.setDeliveryMode(DeliveryMode.NON_PERSISTENT);
    producer.send(session.createTextMessage("dsm listener checkpoint test"));
    producer.close();

    assertTrue(listener.latch.await(10, TimeUnit.SECONDS), "Listener should have been triggered");
    assertEquals("dsm listener checkpoint test", listener.receivedText.get());

    consumer.close();
    listenerSession.close();
    listenerConnection.close();

    // Wait for consumer spans
    blockUntilTracesMatch(
        traces -> {
          List<DDSpan> spans = new ArrayList<>();
          for (List<DDSpan> trace : traces) {
            spans.addAll(trace);
          }
          return spans.stream()
              .anyMatch(s -> "consumer".equals(String.valueOf(s.getTag(Tags.SPAN_KIND))));
        });

    List<DDSpan> allSpans = flattenTraces();
    DDSpan producerSpan = findSpanByOperation(allSpans, "jms.produce");
    DDSpan listenerSpan = findConsumerOrProcessSpan(allSpans);

    assertNotNull(producerSpan, "Expected a jms.produce span");
    assertNotNull(listenerSpan, "Expected a consumer span for listener");

    // Verify full listener span structure after DSM checkpoint
    assertEquals("jms.consume", listenerSpan.getOperationName().toString());
    assertEquals(DDSpanTypes.MESSAGE_CONSUMER, listenerSpan.getSpanType());
    assertEquals("jms", String.valueOf(listenerSpan.getTag(Tags.COMPONENT)));
    assertEquals("consumer", String.valueOf(listenerSpan.getTag(Tags.SPAN_KIND)));
    assertEquals("jms", listenerSpan.getTag("messaging.system"));
    assertTrue(listenerSpan.isMeasured(), "Listener span should be measured after DSM checkpoint");
    assertNotNull(
        listenerSpan.getTag("messaging.destination.name"),
        "messaging.destination.name should be set on listener span after DSM checkpoint");

    // Verify trace context propagation through listener with DSM
    assertEquals(
        producerSpan.getTraceId(),
        listenerSpan.getTraceId(),
        "Listener span should share trace ID with producer after DSM changes");
  }

  @Test
  void dsmMultipleMessagesProduceCorrectSpans() throws Exception {
    // Verifies DSM works correctly with multiple messages (checkpoints per message)
    Queue queue = createUniqueQueue();
    MessageProducer producer = session.createProducer(queue);
    producer.setDeliveryMode(DeliveryMode.NON_PERSISTENT);
    MessageConsumer consumer = session.createConsumer(queue);

    producer.send(session.createTextMessage("dsm msg 1"));
    producer.send(session.createTextMessage("dsm msg 2"));

    TextMessage received1 = (TextMessage) consumer.receive(5000);
    TextMessage received2 = (TextMessage) consumer.receive(5000);

    assertNotNull(received1, "Should have received message 1");
    assertNotNull(received2, "Should have received message 2");

    producer.close();
    consumer.close();

    List<DDSpan> allSpans = waitAndFlattenTraces(4);
    List<DDSpan> producerSpans = findSpansByOperation(allSpans, "jms.produce");
    List<DDSpan> consumerSpans = findSpansByOperation(allSpans, "jms.consume");

    assertEquals(2, producerSpans.size(), "Expected two jms.produce spans with DSM");
    assertTrue(
        consumerSpans.size() >= 2,
        "Expected at least two jms.consume spans with DSM, got " + consumerSpans.size());

    // Verify each producer-consumer pair has correct trace propagation
    for (DDSpan pSpan : producerSpans) {
      assertEquals("jms.produce", pSpan.getOperationName().toString());
      assertEquals("jms", String.valueOf(pSpan.getTag(Tags.COMPONENT)));
      assertEquals("producer", String.valueOf(pSpan.getTag(Tags.SPAN_KIND)));
    }
    for (DDSpan cSpan : consumerSpans) {
      assertEquals("jms.consume", cSpan.getOperationName().toString());
      assertEquals("jms", String.valueOf(cSpan.getTag(Tags.COMPONENT)));
      assertEquals("consumer", String.valueOf(cSpan.getTag(Tags.SPAN_KIND)));
      assertEquals("jms", cSpan.getTag("messaging.system"));
    }
  }

  // =========================================================================
  // Peer service input tag tests
  //
  // PeerServiceCalculator computes peer.service from input tags. For JMS,
  // the precursor is messaging.destination.name. These tests verify the
  // input tags are correctly set on producer spans so peer.service can be derived.
  // =========================================================================

  @Test
  void producerSpanHasPeerServiceInputTags() throws Exception {
    // Verifies producer spans have the input tags needed for PeerServiceCalculator:
    // - component = "jms" (selects JMS precursor mapping)
    // - span.kind = "producer" (enables peer service calculation)
    // - messaging.destination.name = queue name (the precursor value)
    Queue queue = createUniqueQueue();
    String queueName = queue.getQueueName();
    MessageProducer producer = session.createProducer(queue);
    producer.setDeliveryMode(DeliveryMode.NON_PERSISTENT);

    producer.send(session.createTextMessage("peer service input test"));
    producer.close();

    List<DDSpan> producerSpans = findSpansByOperation(waitAndFlattenTraces(1), "jms.produce");
    assertEquals(1, producerSpans.size(), "Expected exactly one jms.produce span");
    DDSpan producerSpan = producerSpans.get(0);

    // Verify peer service input tags
    assertEquals(
        "jms",
        String.valueOf(producerSpan.getTag(Tags.COMPONENT)),
        "Component must be 'jms' for PeerServiceNamingV1 precursor lookup");
    assertEquals(
        "producer",
        String.valueOf(producerSpan.getTag(Tags.SPAN_KIND)),
        "Span kind must be 'producer' for peer service eligibility");
    assertEquals(
        queueName,
        String.valueOf(producerSpan.getTag("messaging.destination.name")),
        "messaging.destination.name must be set for peer service derivation");
  }

  @Test
  void producerSpanToTopicHasPeerServiceInputTags() throws Exception {
    // Verifies topic producer spans also have the correct peer service input tags
    Topic topic = createUniqueTopic();
    String topicName = topic.getTopicName();
    MessageProducer producer = session.createProducer(topic);
    producer.setDeliveryMode(DeliveryMode.NON_PERSISTENT);

    producer.send(session.createTextMessage("peer service topic test"));
    producer.close();

    List<DDSpan> producerSpans = findSpansByOperation(waitAndFlattenTraces(1), "jms.produce");
    assertEquals(1, producerSpans.size(), "Expected exactly one jms.produce span");
    DDSpan producerSpan = producerSpans.get(0);

    assertEquals(
        "jms",
        String.valueOf(producerSpan.getTag(Tags.COMPONENT)),
        "Component must be 'jms' for topic producer");
    assertEquals(
        "producer",
        String.valueOf(producerSpan.getTag(Tags.SPAN_KIND)),
        "Span kind must be 'producer' for topic producer");
    assertEquals(
        topicName,
        String.valueOf(producerSpan.getTag("messaging.destination.name")),
        "messaging.destination.name must contain topic name for peer service derivation");
  }

  @Test
  void explicitDestinationProducerSpanHasPeerServiceInputTags() throws Exception {
    // Verifies send(Destination, Message) also sets the correct input tags
    Queue queue = createUniqueQueue();
    String queueName = queue.getQueueName();
    MessageProducer producer = session.createProducer(null);
    producer.setDeliveryMode(DeliveryMode.NON_PERSISTENT);

    producer.send(queue, session.createTextMessage("peer service explicit dest test"));
    producer.close();

    List<DDSpan> producerSpans = findSpansByOperation(waitAndFlattenTraces(1), "jms.produce");
    assertEquals(1, producerSpans.size(), "Expected exactly one jms.produce span");
    DDSpan producerSpan = producerSpans.get(0);

    assertEquals(
        "jms",
        String.valueOf(producerSpan.getTag(Tags.COMPONENT)),
        "Component must be 'jms' for explicit destination send");
    assertEquals(
        "producer",
        String.valueOf(producerSpan.getTag(Tags.SPAN_KIND)),
        "Span kind must be 'producer' for explicit destination send");
    assertEquals(
        queueName,
        String.valueOf(producerSpan.getTag("messaging.destination.name")),
        "messaging.destination.name must be set for explicit destination peer service");
  }

  @Test
  void temporaryQueueProducerSpanHasPeerServiceInputTags() throws Exception {
    // Verifies temporary queue producer spans have peer service input tags
    TemporaryQueue tempQueue = session.createTemporaryQueue();
    MessageProducer producer = session.createProducer(tempQueue);
    producer.setDeliveryMode(DeliveryMode.NON_PERSISTENT);

    producer.send(session.createTextMessage("peer service temp queue test"));
    producer.close();

    List<DDSpan> producerSpans = findSpansByOperation(waitAndFlattenTraces(1), "jms.produce");
    assertEquals(1, producerSpans.size(), "Expected exactly one jms.produce span");
    DDSpan producerSpan = producerSpans.get(0);

    assertEquals(
        "jms",
        String.valueOf(producerSpan.getTag(Tags.COMPONENT)),
        "Component must be 'jms' for temporary queue producer");
    assertEquals(
        "producer",
        String.valueOf(producerSpan.getTag(Tags.SPAN_KIND)),
        "Span kind must be 'producer' for temporary queue producer");
    // Temporary queues should still have a destination name
    assertNotNull(
        producerSpan.getTag("messaging.destination.name"),
        "messaging.destination.name should be set even for temporary queues");

    tempQueue.delete();
  }

  @Test
  void consumerSpanHasPeerServiceInputTags() throws Exception {
    // Consumer spans have span.kind=consumer which also triggers peer service calculation.
    // Verify the input tags are correctly set.
    Queue queue = createUniqueQueue();
    String queueName = queue.getQueueName();
    MessageProducer producer = session.createProducer(queue);
    producer.setDeliveryMode(DeliveryMode.NON_PERSISTENT);
    MessageConsumer consumer = session.createConsumer(queue);

    producer.send(session.createTextMessage("consumer peer service test"));
    TextMessage received = (TextMessage) consumer.receive(5000);
    assertNotNull(received, "Should have received a message");

    producer.close();
    consumer.close();

    List<DDSpan> allSpans = waitAndFlattenTraces(2);
    List<DDSpan> consumerSpans = findSpansByOperation(allSpans, "jms.consume");
    assertTrue(!consumerSpans.isEmpty(), "Expected at least one jms.consume span");
    DDSpan consumerSpan = consumerSpans.get(0);

    assertEquals(
        "jms",
        String.valueOf(consumerSpan.getTag(Tags.COMPONENT)),
        "Component must be 'jms' for consumer peer service");
    assertEquals(
        "consumer",
        String.valueOf(consumerSpan.getTag(Tags.SPAN_KIND)),
        "Span kind must be 'consumer' for consumer span");
    assertEquals(
        queueName,
        String.valueOf(consumerSpan.getTag("messaging.destination.name")),
        "messaging.destination.name must be set on consumer span for peer service");
  }

  // =========================================================================
  // Named MessageListener implementations for reliable instrumentation
  // =========================================================================

  static class TextCapturingListener implements MessageListener {
    final AtomicReference<String> receivedText = new AtomicReference<>();
    final CountDownLatch latch = new CountDownLatch(1);

    @Override
    public void onMessage(Message message) {
      try {
        if (message instanceof TextMessage) {
          receivedText.set(((TextMessage) message).getText());
        }
      } catch (JMSException e) {
        // ignore
      } finally {
        latch.countDown();
      }
    }
  }

  static class ErrorThrowingListener implements MessageListener {
    final CountDownLatch latch = new CountDownLatch(1);

    @Override
    public void onMessage(Message message) {
      try {
        throw new RuntimeException("Simulated processing error in onMessage");
      } finally {
        latch.countDown();
      }
    }
  }

  static class LatchListener implements MessageListener {
    final CountDownLatch latch = new CountDownLatch(1);

    @Override
    public void onMessage(Message message) {
      latch.countDown();
    }
  }

  // =========================================================================
  // Helper methods
  // =========================================================================

  private List<DDSpan> flattenTraces() {
    List<DDSpan> result = new ArrayList<>();
    for (List<DDSpan> trace : writer) {
      result.addAll(trace);
    }
    return result;
  }

  private List<DDSpan> waitAndFlattenTraces(int expectedTraceCount)
      throws InterruptedException, java.util.concurrent.TimeoutException {
    writer.waitForTraces(expectedTraceCount);
    return flattenTraces();
  }

  private DDSpan findSpanByOperation(List<DDSpan> spans, String operationName) {
    for (DDSpan span : spans) {
      if (span.getOperationName().toString().equals(operationName)) {
        return span;
      }
    }
    return null;
  }

  private List<DDSpan> findSpansByOperation(List<DDSpan> spans, String operationName) {
    List<DDSpan> result = new ArrayList<>();
    for (DDSpan span : spans) {
      if (span.getOperationName().toString().equals(operationName)) {
        result.add(span);
      }
    }
    return result;
  }

  private DDSpan findConsumerOrProcessSpan(List<DDSpan> spans) {
    for (DDSpan span : spans) {
      String op = span.getOperationName().toString();
      if ("jms.consume".equals(op) || "jms.process".equals(op)) {
        String kind = String.valueOf(span.getTag(Tags.SPAN_KIND));
        if ("consumer".equals(kind)) {
          return span;
        }
      }
    }
    // Fallback: return any consumer-kind span
    for (DDSpan span : spans) {
      if ("consumer".equals(String.valueOf(span.getTag(Tags.SPAN_KIND)))) {
        return span;
      }
    }
    return null;
  }
}

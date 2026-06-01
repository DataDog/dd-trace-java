package datadog.trace.instrumentation.jms;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.core.DDSpan;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
import javax.jms.TemporaryTopic;
import javax.jms.TextMessage;
import javax.jms.Topic;
import org.apache.activemq.junit.EmbeddedActiveMQBroker;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Integration tests for JMS 1.1 instrumentation covering producer, consumer, and message listener
 * spans. Validates span structure, operation names, resource names, span types, span kinds, tags,
 * error propagation, and distributed trace context propagation between producer and consumer.
 *
 * <p>Uses an embedded ActiveMQ broker for realistic end-to-end testing.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class JMS1Test extends AbstractInstrumentationTest {

  static {
    // Ensure tracing is enabled before the agent is installed by the parent @BeforeAll.
    // This protects against DD_TRACE_ENABLED=false leaking from the environment.
    System.setProperty("dd.trace.enabled", "true");
  }

  private EmbeddedActiveMQBroker broker;
  private Connection connection;
  private Session session;
  private int queueCounter = 0;

  @BeforeAll
  void setupBroker() throws JMSException {
    broker = new EmbeddedActiveMQBroker();
    broker.start();
    ConnectionFactory connectionFactory = broker.createConnectionFactory();
    connection = connectionFactory.createConnection();
    connection.start();
    session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
  }

  @AfterAll
  void tearDownBroker() throws JMSException {
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

  /** Returns a unique queue for each test to avoid cross-test data leaks. */
  private Queue createUniqueQueue() throws JMSException {
    return session.createQueue("test-queue-" + (queueCounter++));
  }

  // ===================== Producer Tests =====================

  @Test
  void sendToQueueCreatesProducerSpanWithCorrectAttributes()
      throws JMSException, InterruptedException, TimeoutException {
    Queue queue = createUniqueQueue();
    MessageProducer producer = session.createProducer(queue);
    TextMessage message = session.createTextMessage("hello queue");

    producer.send(message);

    producer.close();

    writer.waitForTraces(1);
    List<DDSpan> allSpans = flattenTraces();
    DDSpan producerSpan = findSpanByOperationAndKind(allSpans, "jms.produce", "producer");

    assertNotNull(producerSpan, "Expected a jms.produce span");
    // Operation name
    assertEquals("jms.produce", producerSpan.getOperationName().toString());
    // Span type — JMS producer and consumer spans use type "queue"
    assertEquals("queue", producerSpan.getSpanType());
    // Resource name should contain "Produced for Queue <queueName>"
    String resourceName = producerSpan.getResourceName().toString();
    assertTrue(
        resourceName.contains("Produced for")
            && resourceName.contains("Queue")
            && resourceName.contains(queue.getQueueName()),
        "Resource name should be 'Produced for Queue <name>', got: " + resourceName);
    // Tags
    assertEquals("jms", String.valueOf(producerSpan.getTag("component")));
    assertEquals("producer", String.valueOf(producerSpan.getTag("span.kind")));
    // Messaging semantic convention tags
    assertEquals(
        "jms",
        producerSpan.getTag("messaging.system"),
        "Producer span should have messaging.system=jms");
    assertEquals(
        "send",
        producerSpan.getTag("messaging.operation"),
        "Producer span should have messaging.operation=send");
    // Measured flag
    assertTrue(producerSpan.isMeasured(), "Producer span should be measured");
    // No error
    assertFalse(producerSpan.isError(), "Producer span should not be errored");
    // Root span (no parent)
    assertEquals(0L, producerSpan.getParentId(), "Producer span should be a root span");
  }

  @Test
  void sendToTopicCreatesProducerSpanWithTopicResourceName()
      throws JMSException, InterruptedException, TimeoutException {
    Topic topic = session.createTopic("test-topic-producer");
    MessageProducer producer = session.createProducer(topic);
    TextMessage message = session.createTextMessage("hello topic");

    producer.send(message);

    producer.close();

    writer.waitForTraces(1);
    List<DDSpan> allSpans = flattenTraces();
    DDSpan producerSpan = findSpanByOperationAndKind(allSpans, "jms.produce", "producer");

    assertNotNull(producerSpan, "Expected a jms.produce span for topic");
    assertEquals("jms.produce", producerSpan.getOperationName().toString());
    assertEquals("queue", producerSpan.getSpanType());
    String resourceName = producerSpan.getResourceName().toString();
    assertTrue(
        resourceName.contains("Produced for") && resourceName.contains("Topic"),
        "Resource name should contain 'Produced for' and 'Topic', got: " + resourceName);
    assertEquals("jms", String.valueOf(producerSpan.getTag("component")));
    assertEquals("producer", String.valueOf(producerSpan.getTag("span.kind")));
    assertEquals(
        "jms",
        producerSpan.getTag("messaging.system"),
        "Topic producer span should have messaging.system=jms");
    assertEquals(
        "send",
        producerSpan.getTag("messaging.operation"),
        "Topic producer span should have messaging.operation=send");
    assertFalse(producerSpan.isError());
  }

  @Test
  void sendWithExplicitDestinationCreatesProducerSpan()
      throws JMSException, InterruptedException, TimeoutException {
    Queue queue = createUniqueQueue();
    // Create producer without default destination
    MessageProducer producer = session.createProducer(null);
    TextMessage message = session.createTextMessage("hello explicit dest");

    producer.send(queue, message);

    producer.close();

    writer.waitForTraces(1);
    List<DDSpan> allSpans = flattenTraces();
    DDSpan producerSpan = findSpanByOperationAndKind(allSpans, "jms.produce", "producer");

    assertNotNull(producerSpan, "Expected a jms.produce span for explicit destination");
    assertEquals("jms.produce", producerSpan.getOperationName().toString());
    assertEquals("queue", producerSpan.getSpanType());
    assertEquals("jms", String.valueOf(producerSpan.getTag("component")));
    assertEquals("producer", String.valueOf(producerSpan.getTag("span.kind")));
    assertFalse(producerSpan.isError());
  }

  @Test
  void sendWithDeliveryModeAndPriorityCreatesProducerSpan()
      throws JMSException, InterruptedException, TimeoutException {
    Queue queue = createUniqueQueue();
    MessageProducer producer = session.createProducer(queue);
    TextMessage message = session.createTextMessage("hello with params");

    // Send with explicit deliveryMode, priority, and timeToLive
    producer.send(message, DeliveryMode.NON_PERSISTENT, 7, 60000);

    producer.close();

    writer.waitForTraces(1);
    List<DDSpan> allSpans = flattenTraces();
    DDSpan producerSpan = findSpanByOperationAndKind(allSpans, "jms.produce", "producer");

    assertNotNull(producerSpan, "Expected a jms.produce span with delivery params");
    assertEquals("jms.produce", producerSpan.getOperationName().toString());
    assertEquals("queue", producerSpan.getSpanType());
    assertEquals("jms", String.valueOf(producerSpan.getTag("component")));
    assertEquals("producer", String.valueOf(producerSpan.getTag("span.kind")));
    assertFalse(producerSpan.isError());
  }

  @Test
  void sendToTemporaryQueueCreatesProducerSpanWithTemporaryResourceName()
      throws JMSException, InterruptedException, TimeoutException {
    TemporaryQueue tempQueue = session.createTemporaryQueue();
    MessageProducer producer = session.createProducer(tempQueue);
    TextMessage message = session.createTextMessage("hello temp queue");

    producer.send(message);

    producer.close();
    tempQueue.delete();

    writer.waitForTraces(1);
    List<DDSpan> allSpans = flattenTraces();
    DDSpan producerSpan = findSpanByOperationAndKind(allSpans, "jms.produce", "producer");

    assertNotNull(producerSpan, "Expected a jms.produce span for temporary queue");
    String resourceName = producerSpan.getResourceName().toString();
    assertTrue(
        resourceName.contains("Temporary Queue"),
        "Resource name for temp queue should contain 'Temporary Queue', got: " + resourceName);
    assertEquals("jms", String.valueOf(producerSpan.getTag("component")));
    assertEquals("producer", String.valueOf(producerSpan.getTag("span.kind")));
  }

  @Test
  void sendToTemporaryTopicCreatesProducerSpanWithTemporaryResourceName()
      throws JMSException, InterruptedException, TimeoutException {
    TemporaryTopic tempTopic = session.createTemporaryTopic();
    MessageProducer producer = session.createProducer(tempTopic);
    TextMessage message = session.createTextMessage("hello temp topic");

    producer.send(message);

    producer.close();
    tempTopic.delete();

    writer.waitForTraces(1);
    List<DDSpan> allSpans = flattenTraces();
    DDSpan producerSpan = findSpanByOperationAndKind(allSpans, "jms.produce", "producer");

    assertNotNull(producerSpan, "Expected a jms.produce span for temporary topic");
    String resourceName = producerSpan.getResourceName().toString();
    assertTrue(
        resourceName.contains("Temporary Topic"),
        "Resource name for temp topic should contain 'Temporary Topic', got: " + resourceName);
    assertEquals("jms", String.valueOf(producerSpan.getTag("component")));
    assertEquals("producer", String.valueOf(producerSpan.getTag("span.kind")));
  }

  // ===================== Consumer Tests =====================

  @Test
  void receiveCreatesConsumerSpanLinkedToProducer()
      throws JMSException, InterruptedException, TimeoutException {
    Queue queue = createUniqueQueue();
    MessageProducer producer = session.createProducer(queue);
    MessageConsumer consumer = session.createConsumer(queue);

    TextMessage sentMessage = session.createTextMessage("receive test");
    producer.send(sentMessage);

    TextMessage receivedMessage = (TextMessage) consumer.receive(5000);

    assertNotNull(receivedMessage, "Should have received a message");
    assertEquals("receive test", receivedMessage.getText());

    producer.close();
    consumer.close();

    writer.waitForTraces(2);
    List<DDSpan> allSpans = flattenTraces();
    DDSpan producerSpan = findSpanByOperationAndKind(allSpans, "jms.produce", "producer");
    DDSpan consumerSpan = findSpanByOperationAndKind(allSpans, "jms.consume", "consumer");

    assertNotNull(producerSpan, "Expected a jms.produce span");
    assertNotNull(consumerSpan, "Expected a jms.consume span");

    // Consumer span attributes
    assertEquals("jms.consume", consumerSpan.getOperationName().toString());
    assertEquals("queue", consumerSpan.getSpanType());
    String consumerResource = consumerSpan.getResourceName().toString();
    assertTrue(
        consumerResource.contains("Consumed from") && consumerResource.contains("Queue"),
        "Consumer resource should contain 'Consumed from Queue', got: " + consumerResource);
    assertEquals("jms", String.valueOf(consumerSpan.getTag("component")));
    assertEquals("consumer", String.valueOf(consumerSpan.getTag("span.kind")));
    // Messaging semantic convention tags
    assertEquals(
        "jms",
        consumerSpan.getTag("messaging.system"),
        "Consumer span should have messaging.system=jms");
    assertEquals(
        "receive",
        consumerSpan.getTag("messaging.operation"),
        "Consumer receive() span should have messaging.operation=receive");
    assertTrue(consumerSpan.isMeasured(), "Consumer span should be measured");
    assertFalse(consumerSpan.isError(), "Consumer span should not be errored");

    // Context propagation: consumer should be child of producer
    assertEquals(
        producerSpan.getSpanId(),
        consumerSpan.getParentId(),
        "Consumer span should be child of the producer span (distributed context propagation)");
    assertEquals(
        producerSpan.getTraceId(),
        consumerSpan.getTraceId(),
        "Consumer and producer spans should share the same trace ID");
  }

  @Test
  void receiveWithTimeoutCreatesConsumerSpan()
      throws JMSException, InterruptedException, TimeoutException {
    Queue queue = createUniqueQueue();
    MessageProducer producer = session.createProducer(queue);
    MessageConsumer consumer = session.createConsumer(queue);

    TextMessage sentMessage = session.createTextMessage("timeout receive");
    producer.send(sentMessage);

    TextMessage receivedMessage = (TextMessage) consumer.receive(5000);

    assertNotNull(receivedMessage, "Should have received message with timeout");
    assertEquals("timeout receive", receivedMessage.getText());

    producer.close();
    consumer.close();

    writer.waitForTraces(2);
    List<DDSpan> allSpans = flattenTraces();
    DDSpan consumerSpan = findSpanByOperationAndKind(allSpans, "jms.consume", "consumer");

    assertNotNull(consumerSpan, "Expected a jms.consume span for receive(timeout)");
    assertEquals("jms.consume", consumerSpan.getOperationName().toString());
    assertEquals("queue", consumerSpan.getSpanType());
    assertEquals("jms", String.valueOf(consumerSpan.getTag("component")));
    assertEquals("consumer", String.valueOf(consumerSpan.getTag("span.kind")));
    assertFalse(consumerSpan.isError());
  }

  @Test
  void receiveNoWaitCreatesConsumerSpan()
      throws JMSException, InterruptedException, TimeoutException {
    Queue queue = createUniqueQueue();
    MessageProducer producer = session.createProducer(queue);
    MessageConsumer consumer = session.createConsumer(queue);

    TextMessage sentMessage = session.createTextMessage("noWait receive");
    producer.send(sentMessage);

    // Allow time for the message to arrive at the broker
    Thread.sleep(500);

    TextMessage receivedMessage = (TextMessage) consumer.receiveNoWait();

    assertNotNull(receivedMessage, "Should have received message with receiveNoWait");
    assertEquals("noWait receive", receivedMessage.getText());

    producer.close();
    consumer.close();

    writer.waitForTraces(2);
    List<DDSpan> allSpans = flattenTraces();
    DDSpan consumerSpan = findSpanByOperationAndKind(allSpans, "jms.consume", "consumer");

    assertNotNull(consumerSpan, "Expected a jms.consume span for receiveNoWait");
    assertEquals("jms.consume", consumerSpan.getOperationName().toString());
    assertEquals("queue", consumerSpan.getSpanType());
    assertEquals("jms", String.valueOf(consumerSpan.getTag("component")));
    assertEquals("consumer", String.valueOf(consumerSpan.getTag("span.kind")));
    assertFalse(consumerSpan.isError());
  }

  @Test
  void multipleMessagesCreateMultipleProducerAndConsumerSpans()
      throws JMSException, InterruptedException, TimeoutException {
    Queue queue = createUniqueQueue();
    MessageProducer producer = session.createProducer(queue);
    MessageConsumer consumer = session.createConsumer(queue);

    producer.send(session.createTextMessage("msg1"));
    producer.send(session.createTextMessage("msg2"));
    producer.send(session.createTextMessage("msg3"));

    TextMessage recv1 = (TextMessage) consumer.receive(5000);
    TextMessage recv2 = (TextMessage) consumer.receive(5000);
    TextMessage recv3 = (TextMessage) consumer.receive(5000);

    assertNotNull(recv1, "Should receive message 1");
    assertNotNull(recv2, "Should receive message 2");
    assertNotNull(recv3, "Should receive message 3");
    assertEquals("msg1", recv1.getText());
    assertEquals("msg2", recv2.getText());
    assertEquals("msg3", recv3.getText());

    producer.close();

    // Trigger last pending consumer span to finish (receiveNoWait causes previous span flush)
    consumer.receiveNoWait();
    consumer.close();

    // 3 producer traces + up to 3 consumer traces
    writer.waitForTraces(6);
    List<DDSpan> allSpans = flattenTraces();

    List<DDSpan> producerSpans = findAllSpansByOperation(allSpans, "jms.produce");
    List<DDSpan> consumerSpans = findAllSpansByOperation(allSpans, "jms.consume");

    assertEquals(3, producerSpans.size(), "Expected 3 producer spans");
    assertEquals(3, consumerSpans.size(), "Expected 3 consumer spans");

    // Each producer span should have consistent attributes
    for (DDSpan pSpan : producerSpans) {
      assertEquals("producer", String.valueOf(pSpan.getTag("span.kind")));
      assertEquals("jms", String.valueOf(pSpan.getTag("component")));
      assertEquals("queue", pSpan.getSpanType());
    }

    // Each consumer span should be linked to a producer span
    for (DDSpan cSpan : consumerSpans) {
      assertEquals("consumer", String.valueOf(cSpan.getTag("span.kind")));
      assertEquals("jms", String.valueOf(cSpan.getTag("component")));
      assertEquals("queue", cSpan.getSpanType());
      // Consumer should be child of a producer (distributed context)
      assertTrue(
          cSpan.getParentId() != 0,
          "Consumer span should have a parent (linked via context propagation)");
    }
  }

  @Test
  void consumerReceiveFromTopicCreatesConsumerSpan()
      throws JMSException, InterruptedException, TimeoutException {
    Topic topic = session.createTopic("test-topic-consumer");
    MessageProducer producer = session.createProducer(topic);
    MessageConsumer consumer = session.createConsumer(topic);

    TextMessage sentMessage = session.createTextMessage("topic consume test");
    producer.send(sentMessage);

    TextMessage receivedMessage = (TextMessage) consumer.receive(5000);
    assertNotNull(receivedMessage, "Should have received topic message");
    assertEquals("topic consume test", receivedMessage.getText());

    producer.close();
    consumer.close();

    writer.waitForTraces(2);
    List<DDSpan> allSpans = flattenTraces();
    DDSpan consumerSpan = findSpanByOperationAndKind(allSpans, "jms.consume", "consumer");

    assertNotNull(consumerSpan, "Expected a jms.consume span for topic");
    String consumerResource = consumerSpan.getResourceName().toString();
    assertTrue(
        consumerResource.contains("Consumed from") && consumerResource.contains("Topic"),
        "Consumer resource should mention 'Consumed from Topic', got: " + consumerResource);
    assertEquals("jms", String.valueOf(consumerSpan.getTag("component")));
    assertEquals("consumer", String.valueOf(consumerSpan.getTag("span.kind")));
    assertEquals(
        "jms",
        consumerSpan.getTag("messaging.system"),
        "Topic consumer span should have messaging.system=jms");
    assertEquals(
        "receive",
        consumerSpan.getTag("messaging.operation"),
        "Topic consumer span should have messaging.operation=receive");
  }

  // ===================== MessageListener Tests =====================

  @Test
  void messageListenerOnMessageCreatesConsumerSpanLinkedToProducer()
      throws JMSException, InterruptedException, TimeoutException {
    Queue queue = createUniqueQueue();
    MessageProducer producer = session.createProducer(queue);
    MessageConsumer consumer = session.createConsumer(queue);

    CountDownLatch latch = new CountDownLatch(1);
    TestMessageListener listener = new TestMessageListener(latch);
    consumer.setMessageListener(listener);

    TextMessage sentMessage = session.createTextMessage("listener test");
    producer.send(sentMessage);

    assertTrue(latch.await(10, TimeUnit.SECONDS), "Listener should receive the message");
    assertNotNull(listener.receivedMessage, "Listener should have captured the message");

    producer.close();
    consumer.close();

    writer.waitForTraces(2);
    List<DDSpan> allSpans = flattenTraces();
    DDSpan producerSpan = findSpanByOperationAndKind(allSpans, "jms.produce", "producer");

    assertNotNull(producerSpan, "Expected a jms.produce span");

    // Find the consumer/deliver span created by the MessageListener instrumentation
    DDSpan listenerSpan = findConsumerOrDeliverSpan(allSpans);
    assertNotNull(
        listenerSpan, "Expected a consumer or deliver span for MessageListener.onMessage");
    assertEquals("queue", listenerSpan.getSpanType());
    // Resource name should follow the 'Consumed from Queue <name>' pattern
    String listenerResource = listenerSpan.getResourceName().toString();
    assertTrue(
        listenerResource.contains("Consumed from") && listenerResource.contains("Queue"),
        "Listener span resource should contain 'Consumed from Queue', got: " + listenerResource);
    assertEquals("jms", String.valueOf(listenerSpan.getTag("component")));
    String listenerKind = String.valueOf(listenerSpan.getTag("span.kind"));
    assertTrue(
        "consumer".equals(listenerKind) || "broker".equals(listenerKind),
        "Listener span kind should be 'consumer' or 'broker', got: " + listenerKind);
    // Messaging semantic convention tags for listener (process operation)
    assertEquals(
        "jms",
        listenerSpan.getTag("messaging.system"),
        "Listener span should have messaging.system=jms");
    assertEquals(
        "process",
        listenerSpan.getTag("messaging.operation"),
        "MessageListener.onMessage span should have messaging.operation=process");
    assertFalse(listenerSpan.isError(), "Listener span should not be errored");
    // Linked to producer via context propagation
    assertEquals(
        producerSpan.getTraceId(),
        listenerSpan.getTraceId(),
        "Listener span should share trace ID with producer");
  }

  @Test
  void messageListenerErrorIsRecordedOnSpan()
      throws JMSException, InterruptedException, TimeoutException {
    Queue queue = createUniqueQueue();
    MessageProducer producer = session.createProducer(queue);
    MessageConsumer consumer = session.createConsumer(queue);

    CountDownLatch latch = new CountDownLatch(1);
    FailingMessageListener listener = new FailingMessageListener(latch);
    consumer.setMessageListener(listener);

    TextMessage sentMessage = session.createTextMessage("error listener test");
    producer.send(sentMessage);

    assertTrue(latch.await(10, TimeUnit.SECONDS), "Failing listener should process the message");

    producer.close();
    consumer.close();

    writer.waitForTraces(2);
    List<DDSpan> allSpans = flattenTraces();

    // Find the listener span — it should be errored
    DDSpan listenerSpan = findConsumerOrDeliverSpan(allSpans);
    assertNotNull(listenerSpan, "Expected a consumer/deliver span for failing listener");
    assertTrue(listenerSpan.isError(), "Listener span should be marked as errored");
    assertNotNull(
        listenerSpan.getTag("error.message"), "Errored span should have error.message tag");
    assertNotNull(listenerSpan.getTag("error.type"), "Errored span should have error.type tag");
    assertNotNull(listenerSpan.getTag("error.stack"), "Errored span should have error.stack tag");
  }

  // ===================== Context Propagation Tests =====================

  @Test
  void producerSpanIsChildOfActiveSpan()
      throws JMSException, InterruptedException, TimeoutException {
    Queue queue = createUniqueQueue();
    MessageProducer producer = session.createProducer(queue);

    AgentSpan parentSpan = startSpan("test", "parent");
    AgentScope parentScope = activateSpan(parentSpan);
    try {
      TextMessage message = session.createTextMessage("child span test");
      producer.send(message);
    } finally {
      parentScope.close();
      parentSpan.finish();
    }

    producer.close();

    writer.waitForTraces(1);
    List<DDSpan> allSpans = flattenTraces();
    DDSpan producerSpan = findSpanByOperationAndKind(allSpans, "jms.produce", "producer");

    assertNotNull(producerSpan, "Expected a jms.produce span");
    assertEquals(
        parentSpan.getSpanId(),
        producerSpan.getParentId(),
        "Producer span should be a child of the active parent span");
    assertEquals(
        parentSpan.context().getTraceId(),
        producerSpan.getTraceId(),
        "Producer and parent should share trace ID");
  }

  @Test
  void distributedContextPropagatesThroughQueueSendAndReceive()
      throws JMSException, InterruptedException, TimeoutException {
    Queue queue = createUniqueQueue();
    MessageProducer producer = session.createProducer(queue);
    MessageConsumer consumer = session.createConsumer(queue);

    // Send under a parent span to verify full trace linkage
    AgentSpan parentSpan = startSpan("test", "parent");
    AgentScope parentScope = activateSpan(parentSpan);
    try {
      TextMessage message = session.createTextMessage("distributed context test");
      producer.send(message);
    } finally {
      parentScope.close();
      parentSpan.finish();
    }

    TextMessage receivedMessage = (TextMessage) consumer.receive(5000);
    assertNotNull(receivedMessage, "Should receive message with distributed context");
    assertEquals("distributed context test", receivedMessage.getText());

    producer.close();
    consumer.close();

    writer.waitForTraces(2);
    List<DDSpan> allSpans = flattenTraces();
    DDSpan producerSpan = findSpanByOperationAndKind(allSpans, "jms.produce", "producer");
    DDSpan consumerSpan = findSpanByOperationAndKind(allSpans, "jms.consume", "consumer");

    assertNotNull(producerSpan, "Expected a jms.produce span");
    assertNotNull(consumerSpan, "Expected a jms.consume span");

    // Verify the full trace chain: parent -> producer -> consumer
    assertEquals(
        parentSpan.getSpanId(), producerSpan.getParentId(), "Producer should be child of parent");
    assertEquals(
        producerSpan.getSpanId(),
        consumerSpan.getParentId(),
        "Consumer should be child of producer (distributed context)");
    assertEquals(
        parentSpan.context().getTraceId(),
        consumerSpan.getTraceId(),
        "All spans should share the same trace ID");
  }

  @Test
  void distributedContextPropagatesThroughTopicSendAndReceive()
      throws JMSException, InterruptedException, TimeoutException {
    Topic topic = session.createTopic("test-topic-ctx-prop");
    MessageProducer producer = session.createProducer(topic);
    MessageConsumer consumer = session.createConsumer(topic);

    AgentSpan parentSpan = startSpan("test", "parent");
    AgentScope parentScope = activateSpan(parentSpan);
    try {
      TextMessage message = session.createTextMessage("topic context propagation");
      producer.send(message);
    } finally {
      parentScope.close();
      parentSpan.finish();
    }

    TextMessage receivedMessage = (TextMessage) consumer.receive(5000);
    assertNotNull(receivedMessage, "Should receive topic message with distributed context");
    assertEquals("topic context propagation", receivedMessage.getText());

    producer.close();
    consumer.close();

    writer.waitForTraces(2);
    List<DDSpan> allSpans = flattenTraces();
    DDSpan producerSpan = findSpanByOperationAndKind(allSpans, "jms.produce", "producer");
    DDSpan consumerSpan = findSpanByOperationAndKind(allSpans, "jms.consume", "consumer");

    assertNotNull(producerSpan, "Expected a jms.produce span");
    assertNotNull(consumerSpan, "Expected a jms.consume span");

    // Verify producer span structure
    assertEquals("jms.produce", producerSpan.getOperationName().toString());
    assertEquals("queue", producerSpan.getSpanType());
    assertEquals("jms", String.valueOf(producerSpan.getTag("component")));
    assertEquals("producer", String.valueOf(producerSpan.getTag("span.kind")));
    String producerResource = producerSpan.getResourceName().toString();
    assertTrue(
        producerResource.contains("Produced for") && producerResource.contains("Topic"),
        "Producer resource should contain 'Produced for Topic', got: " + producerResource);

    // Verify consumer span structure
    assertEquals("jms.consume", consumerSpan.getOperationName().toString());
    assertEquals("queue", consumerSpan.getSpanType());
    assertEquals("jms", String.valueOf(consumerSpan.getTag("component")));
    assertEquals("consumer", String.valueOf(consumerSpan.getTag("span.kind")));
    String consumerResource = consumerSpan.getResourceName().toString();
    assertTrue(
        consumerResource.contains("Consumed from") && consumerResource.contains("Topic"),
        "Consumer resource should contain 'Consumed from Topic', got: " + consumerResource);

    // Verify the full trace chain: parent -> producer -> consumer
    assertEquals(
        parentSpan.getSpanId(), producerSpan.getParentId(), "Producer should be child of parent");
    assertEquals(
        producerSpan.getSpanId(),
        consumerSpan.getParentId(),
        "Consumer should be child of producer (distributed context via topic)");
    assertEquals(
        parentSpan.context().getTraceId(),
        consumerSpan.getTraceId(),
        "All spans should share the same trace ID");
  }

  @Test
  void distributedContextPropagatesThroughExplicitDestinationSend()
      throws JMSException, InterruptedException, TimeoutException {
    Queue queue = createUniqueQueue();
    // Create producer without default destination (explicit destination on send)
    MessageProducer producer = session.createProducer(null);
    MessageConsumer consumer = session.createConsumer(queue);

    AgentSpan parentSpan = startSpan("test", "parent");
    AgentScope parentScope = activateSpan(parentSpan);
    try {
      TextMessage message = session.createTextMessage("explicit dest context propagation");
      producer.send(queue, message);
    } finally {
      parentScope.close();
      parentSpan.finish();
    }

    TextMessage receivedMessage = (TextMessage) consumer.receive(5000);
    assertNotNull(receivedMessage, "Should receive message sent with explicit destination");
    assertEquals("explicit dest context propagation", receivedMessage.getText());

    producer.close();
    consumer.close();

    writer.waitForTraces(2);
    List<DDSpan> allSpans = flattenTraces();
    DDSpan producerSpan = findSpanByOperationAndKind(allSpans, "jms.produce", "producer");
    DDSpan consumerSpan = findSpanByOperationAndKind(allSpans, "jms.consume", "consumer");

    assertNotNull(producerSpan, "Expected a jms.produce span");
    assertNotNull(consumerSpan, "Expected a jms.consume span");

    // Verify producer span structure for explicit destination send
    assertEquals("jms.produce", producerSpan.getOperationName().toString());
    assertEquals("queue", producerSpan.getSpanType());
    assertEquals("jms", String.valueOf(producerSpan.getTag("component")));
    assertEquals("producer", String.valueOf(producerSpan.getTag("span.kind")));
    assertTrue(producerSpan.isMeasured(), "Producer span should be measured");

    // Verify consumer span structure
    assertEquals("jms.consume", consumerSpan.getOperationName().toString());
    assertEquals("queue", consumerSpan.getSpanType());
    assertEquals("jms", String.valueOf(consumerSpan.getTag("component")));
    assertEquals("consumer", String.valueOf(consumerSpan.getTag("span.kind")));
    assertTrue(consumerSpan.isMeasured(), "Consumer span should be measured");
    String consumerResource = consumerSpan.getResourceName().toString();
    assertTrue(
        consumerResource.contains("Consumed from") && consumerResource.contains("Queue"),
        "Consumer resource should contain 'Consumed from Queue', got: " + consumerResource);

    // Verify context propagated even with explicit destination send
    assertEquals(
        parentSpan.getSpanId(), producerSpan.getParentId(), "Producer should be child of parent");
    assertEquals(
        producerSpan.getSpanId(),
        consumerSpan.getParentId(),
        "Consumer should be child of producer (explicit destination send)");
    assertEquals(
        parentSpan.context().getTraceId(),
        consumerSpan.getTraceId(),
        "All spans should share the same trace ID");
  }

  @Test
  void distributedContextPropagatesThroughMessageListener()
      throws JMSException, InterruptedException, TimeoutException {
    Queue queue = createUniqueQueue();
    MessageProducer producer = session.createProducer(queue);
    MessageConsumer consumer = session.createConsumer(queue);

    CountDownLatch latch = new CountDownLatch(1);
    TestMessageListener listener = new TestMessageListener(latch);
    consumer.setMessageListener(listener);

    AgentSpan parentSpan = startSpan("test", "parent");
    AgentScope parentScope = activateSpan(parentSpan);
    try {
      TextMessage message = session.createTextMessage("listener context propagation");
      producer.send(message);
    } finally {
      parentScope.close();
      parentSpan.finish();
    }

    assertTrue(latch.await(10, TimeUnit.SECONDS), "Listener should receive the message");
    assertNotNull(listener.receivedMessage, "Listener should have captured the message");

    producer.close();
    consumer.close();

    writer.waitForTraces(2);
    List<DDSpan> allSpans = flattenTraces();
    DDSpan producerSpan = findSpanByOperationAndKind(allSpans, "jms.produce", "producer");

    assertNotNull(producerSpan, "Expected a jms.produce span");

    // Verify producer span structure
    assertEquals("jms.produce", producerSpan.getOperationName().toString());
    assertEquals("queue", producerSpan.getSpanType());
    assertEquals("jms", String.valueOf(producerSpan.getTag("component")));
    assertEquals("producer", String.valueOf(producerSpan.getTag("span.kind")));
    assertTrue(producerSpan.isMeasured(), "Producer span should be measured");

    // Find consumer/deliver span created by MessageListener
    DDSpan listenerSpan = findConsumerOrDeliverSpan(allSpans);
    assertNotNull(listenerSpan, "Expected a consumer or deliver span from MessageListener");

    // Verify listener span structure
    assertEquals("queue", listenerSpan.getSpanType());
    assertEquals("jms", String.valueOf(listenerSpan.getTag("component")));
    String listenerKind = String.valueOf(listenerSpan.getTag("span.kind"));
    assertTrue(
        "consumer".equals(listenerKind) || "broker".equals(listenerKind),
        "Listener span kind should be 'consumer' or 'broker', got: " + listenerKind);
    assertFalse(listenerSpan.isError(), "Listener span should not be errored");

    // Producer should be child of parent
    assertEquals(
        parentSpan.getSpanId(), producerSpan.getParentId(), "Producer should be child of parent");

    // All spans in same trace — verifies context propagated through message properties
    assertEquals(
        parentSpan.context().getTraceId(),
        producerSpan.getTraceId(),
        "Producer should share trace ID with parent");
    assertEquals(
        parentSpan.context().getTraceId(),
        listenerSpan.getTraceId(),
        "Listener span should share trace ID with parent (distributed context via listener)");
  }

  @Test
  void distributedContextPropagatesThroughReceiveNoWait()
      throws JMSException, InterruptedException, TimeoutException {
    Queue queue = createUniqueQueue();
    MessageProducer producer = session.createProducer(queue);
    MessageConsumer consumer = session.createConsumer(queue);

    AgentSpan parentSpan = startSpan("test", "parent");
    AgentScope parentScope = activateSpan(parentSpan);
    try {
      TextMessage message = session.createTextMessage("receiveNoWait context propagation");
      producer.send(message);
    } finally {
      parentScope.close();
      parentSpan.finish();
    }

    // Allow time for message to arrive at broker
    Thread.sleep(500);

    TextMessage receivedMessage = (TextMessage) consumer.receiveNoWait();
    assertNotNull(receivedMessage, "Should receive message with receiveNoWait");
    assertEquals("receiveNoWait context propagation", receivedMessage.getText());

    producer.close();
    consumer.close();

    writer.waitForTraces(2);
    List<DDSpan> allSpans = flattenTraces();
    DDSpan producerSpan = findSpanByOperationAndKind(allSpans, "jms.produce", "producer");
    DDSpan consumerSpan = findSpanByOperationAndKind(allSpans, "jms.consume", "consumer");

    assertNotNull(producerSpan, "Expected a jms.produce span");
    assertNotNull(consumerSpan, "Expected a jms.consume span");

    // Verify producer span structure
    assertEquals("jms.produce", producerSpan.getOperationName().toString());
    assertEquals("queue", producerSpan.getSpanType());
    assertEquals("jms", String.valueOf(producerSpan.getTag("component")));
    assertEquals("producer", String.valueOf(producerSpan.getTag("span.kind")));

    // Verify consumer span structure
    assertEquals("jms.consume", consumerSpan.getOperationName().toString());
    assertEquals("queue", consumerSpan.getSpanType());
    assertEquals("jms", String.valueOf(consumerSpan.getTag("component")));
    assertEquals("consumer", String.valueOf(consumerSpan.getTag("span.kind")));
    String consumerResource = consumerSpan.getResourceName().toString();
    assertTrue(
        consumerResource.contains("Consumed from") && consumerResource.contains("Queue"),
        "Consumer resource should contain 'Consumed from Queue', got: " + consumerResource);

    // Verify context propagated through receiveNoWait
    assertEquals(
        producerSpan.getSpanId(),
        consumerSpan.getParentId(),
        "Consumer should be child of producer (context propagation via receiveNoWait)");
    assertEquals(
        parentSpan.context().getTraceId(),
        consumerSpan.getTraceId(),
        "All spans should share the same trace ID");
  }

  // ===================== Error Handling Tests =====================

  @Test
  void sendOnClosedProducerRecordsError()
      throws JMSException, InterruptedException, TimeoutException {
    Queue queue = createUniqueQueue();
    MessageProducer producer = session.createProducer(queue);
    producer.close();

    JMSException caughtException = null;
    try {
      TextMessage message = session.createTextMessage("error test");
      producer.send(message);
    } catch (JMSException e) {
      caughtException = e;
    }

    assertNotNull(caughtException, "Sending on closed producer should throw JMSException");

    // Allow time for any spans to complete
    Thread.sleep(500);

    List<DDSpan> allSpans = flattenTraces();
    DDSpan producerSpan = findSpanByOperationAndKind(allSpans, "jms.produce", "producer");

    // If the instrumentation creates a span around the failed send, it should be errored
    if (producerSpan != null) {
      assertTrue(producerSpan.isError(), "Producer span on failed send should be errored");
      assertNotNull(
          producerSpan.getTag("error.type"), "Errored producer span should have error.type tag");
      assertNotNull(
          producerSpan.getTag("error.message"),
          "Errored producer span should have error.message tag");
    }
  }

  // ===================== Peer Service / Messaging Destination Tests =====================

  @Test
  void producerSpanHasMessagingDestinationNameTag()
      throws JMSException, InterruptedException, TimeoutException {
    Queue queue = createUniqueQueue();
    MessageProducer producer = session.createProducer(queue);
    TextMessage message = session.createTextMessage("peer service test");

    producer.send(message);

    producer.close();

    writer.waitForTraces(1);
    List<DDSpan> allSpans = flattenTraces();
    DDSpan producerSpan = findSpanByOperationAndKind(allSpans, "jms.produce", "producer");

    assertNotNull(producerSpan, "Expected a jms.produce span");
    assertEquals("jms.produce", producerSpan.getOperationName().toString());
    assertEquals("queue", producerSpan.getSpanType());
    assertEquals("jms", String.valueOf(producerSpan.getTag("component")));
    assertEquals("producer", String.valueOf(producerSpan.getTag("span.kind")));
    assertTrue(producerSpan.isMeasured(), "Producer span should be measured");

    // Verify messaging.destination.name tag is set for peer service computation
    Object destinationTag = producerSpan.getTag("messaging.destination.name");
    assertNotNull(
        destinationTag,
        "Producer span should have 'messaging.destination.name' tag for peer service");
    String destinationName = String.valueOf(destinationTag);
    assertTrue(
        destinationName.contains(queue.getQueueName()),
        "messaging.destination.name should contain the queue name '"
            + queue.getQueueName()
            + "', got: "
            + destinationName);
  }

  @Test
  void producerSpanToTopicHasMessagingDestinationNameTag()
      throws JMSException, InterruptedException, TimeoutException {
    Topic topic = session.createTopic("test-topic-peer-svc");
    MessageProducer producer = session.createProducer(topic);
    TextMessage message = session.createTextMessage("topic peer service test");

    producer.send(message);

    producer.close();

    writer.waitForTraces(1);
    List<DDSpan> allSpans = flattenTraces();
    DDSpan producerSpan = findSpanByOperationAndKind(allSpans, "jms.produce", "producer");

    assertNotNull(producerSpan, "Expected a jms.produce span for topic");
    assertEquals("jms.produce", producerSpan.getOperationName().toString());
    assertEquals("queue", producerSpan.getSpanType());
    assertEquals("jms", String.valueOf(producerSpan.getTag("component")));
    assertEquals("producer", String.valueOf(producerSpan.getTag("span.kind")));

    // Verify messaging.destination.name tag is set for topic
    Object destinationTag = producerSpan.getTag("messaging.destination.name");
    assertNotNull(
        destinationTag, "Producer span should have 'messaging.destination.name' tag for topic");
    String destinationName = String.valueOf(destinationTag);
    assertTrue(
        destinationName.contains("test-topic-peer-svc"),
        "messaging.destination.name should contain the topic name, got: " + destinationName);
  }

  @Test
  void explicitDestinationSendHasMessagingDestinationNameTag()
      throws JMSException, InterruptedException, TimeoutException {
    Queue queue = createUniqueQueue();
    MessageProducer producer = session.createProducer(null);
    TextMessage message = session.createTextMessage("explicit dest peer service");

    producer.send(queue, message);

    producer.close();

    writer.waitForTraces(1);
    List<DDSpan> allSpans = flattenTraces();
    DDSpan producerSpan = findSpanByOperationAndKind(allSpans, "jms.produce", "producer");

    assertNotNull(producerSpan, "Expected a jms.produce span for explicit destination");
    assertEquals("jms.produce", producerSpan.getOperationName().toString());
    assertEquals("queue", producerSpan.getSpanType());
    assertEquals("jms", String.valueOf(producerSpan.getTag("component")));
    assertEquals("producer", String.valueOf(producerSpan.getTag("span.kind")));

    // Verify messaging.destination.name tag is set for explicit destination send
    Object destinationTag = producerSpan.getTag("messaging.destination.name");
    assertNotNull(
        destinationTag,
        "Producer span should have 'messaging.destination.name' for explicit destination send");
    String destinationName = String.valueOf(destinationTag);
    assertTrue(
        destinationName.contains(queue.getQueueName()),
        "messaging.destination.name should contain the queue name, got: " + destinationName);
  }

  @Test
  void consumerSpanHasMessagingDestinationNameTag()
      throws JMSException, InterruptedException, TimeoutException {
    Queue queue = createUniqueQueue();
    MessageProducer producer = session.createProducer(queue);
    MessageConsumer consumer = session.createConsumer(queue);

    TextMessage sentMessage = session.createTextMessage("consumer peer service test");
    producer.send(sentMessage);

    TextMessage receivedMessage = (TextMessage) consumer.receive(5000);
    assertNotNull(receivedMessage, "Should have received a message");

    producer.close();
    consumer.close();

    writer.waitForTraces(2);
    List<DDSpan> allSpans = flattenTraces();
    DDSpan consumerSpan = findSpanByOperationAndKind(allSpans, "jms.consume", "consumer");

    assertNotNull(consumerSpan, "Expected a jms.consume span");
    assertEquals("jms.consume", consumerSpan.getOperationName().toString());
    assertEquals("queue", consumerSpan.getSpanType());
    assertEquals("jms", String.valueOf(consumerSpan.getTag("component")));
    assertEquals("consumer", String.valueOf(consumerSpan.getTag("span.kind")));
    assertTrue(consumerSpan.isMeasured(), "Consumer span should be measured");

    // Verify messaging.destination.name tag is set on consumer span
    Object destinationTag = consumerSpan.getTag("messaging.destination.name");
    assertNotNull(
        destinationTag,
        "Consumer span should have 'messaging.destination.name' tag for peer service");
    String destinationName = String.valueOf(destinationTag);
    assertTrue(
        destinationName.contains(queue.getQueueName()),
        "messaging.destination.name should contain the queue name, got: " + destinationName);
  }

  // ===================== Transacted Session Tests =====================

  @Test
  void receiveInTransactedSessionCreatesConsumerSpanOnCommit()
      throws JMSException, InterruptedException, TimeoutException {
    Queue queue = createUniqueQueue();
    Session transactedSession = connection.createSession(true, Session.SESSION_TRANSACTED);
    MessageProducer producer = session.createProducer(queue);
    MessageConsumer consumer = transactedSession.createConsumer(queue);

    producer.send(session.createTextMessage("transacted msg1"));
    producer.send(session.createTextMessage("transacted msg2"));

    TextMessage recv1 = (TextMessage) consumer.receive(5000);
    TextMessage recv2 = (TextMessage) consumer.receive(5000);
    transactedSession.commit();

    assertNotNull(recv1, "Should receive first transacted message");
    assertNotNull(recv2, "Should receive second transacted message");
    assertEquals("transacted msg1", recv1.getText());
    assertEquals("transacted msg2", recv2.getText());

    producer.close();
    consumer.close();
    transactedSession.close();

    writer.waitForTraces(4);
    List<DDSpan> allSpans = flattenTraces();

    List<DDSpan> producerSpans = findAllSpansByOperation(allSpans, "jms.produce");
    List<DDSpan> consumerSpans = findAllSpansByOperation(allSpans, "jms.consume");

    assertEquals(2, producerSpans.size(), "Expected 2 producer spans");
    assertEquals(2, consumerSpans.size(), "Expected 2 consumer spans after commit");

    for (DDSpan cSpan : consumerSpans) {
      assertEquals("consumer", String.valueOf(cSpan.getTag("span.kind")));
      assertEquals("jms", String.valueOf(cSpan.getTag("component")));
      assertFalse(cSpan.isError());
    }
  }

  // ===================== Helper Methods =====================

  private List<DDSpan> flattenTraces() {
    List<DDSpan> result = new ArrayList<>();
    for (List<DDSpan> trace : writer) {
      result.addAll(trace);
    }
    return result;
  }

  private DDSpan findSpanByOperationAndKind(
      List<DDSpan> spans, String operationName, String spanKind) {
    for (DDSpan span : spans) {
      if (span.getOperationName().toString().equals(operationName)
          && spanKind.equals(String.valueOf(span.getTag("span.kind")))) {
        return span;
      }
    }
    return null;
  }

  private List<DDSpan> findAllSpansByOperation(List<DDSpan> spans, String operationName) {
    List<DDSpan> result = new ArrayList<>();
    for (DDSpan span : spans) {
      if (span.getOperationName().toString().equals(operationName)) {
        result.add(span);
      }
    }
    return result;
  }

  /** Finds the consumer or deliver span created by MessageListener instrumentation. */
  private DDSpan findConsumerOrDeliverSpan(List<DDSpan> spans) {
    for (DDSpan span : spans) {
      String opName = span.getOperationName().toString();
      if ("jms.consume".equals(opName) || "jms.deliver".equals(opName)) {
        return span;
      }
    }
    return null;
  }

  /** Simple MessageListener that captures the received message. */
  static class TestMessageListener implements MessageListener {
    volatile Message receivedMessage;
    private final CountDownLatch latch;

    TestMessageListener(CountDownLatch latch) {
      this.latch = latch;
    }

    @Override
    public void onMessage(Message message) {
      receivedMessage = message;
      latch.countDown();
    }
  }

  /** MessageListener that throws a RuntimeException to test error recording. */
  static class FailingMessageListener implements MessageListener {
    private final CountDownLatch latch;

    FailingMessageListener(CountDownLatch latch) {
      this.latch = latch;
    }

    @Override
    public void onMessage(Message message) {
      latch.countDown();
      throw new RuntimeException("Intentional error in message listener");
    }
  }
}

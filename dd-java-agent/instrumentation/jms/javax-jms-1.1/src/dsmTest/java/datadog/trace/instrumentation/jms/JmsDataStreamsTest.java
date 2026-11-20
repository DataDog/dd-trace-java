package datadog.trace.instrumentation.jms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.amazon.sqs.javamessaging.SqsTextMessage;
import com.ibm.msgclient.jms.IbmMqTextMessage;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.core.datastreams.StatsGroup;
import datadog.trace.test.junit.utils.config.WithConfig;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import javax.jms.Connection;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.MessageProducer;
import javax.jms.Session;
import javax.jms.TextMessage;
import org.apache.activemq.artemis.jms.client.ArtemisTextMessage;
import org.apache.activemq.command.ActiveMQTextMessage;
import org.apache.activemq.junit.EmbeddedActiveMQBroker;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@WithConfig(key = "data.streams.enabled", value = "true")
@WithConfig(key = "jms.propagation.disabled.queues", value = "dsmDisabledQueue")
public final class JmsDataStreamsTest extends AbstractInstrumentationTest {
  private static final String PATHWAY_PROPERTY = "dd__dash__pathway__dash__ctx__dash__base64";
  private static final String MESSAGE_TEXT = "a message";
  private static final String QUEUE = "dsmQueue";
  private static final String IBM_QUEUE = "dsmIbmQueue";
  private static final String DISABLED_QUEUE = "dsmDisabledQueue";
  private static final String LISTENER_QUEUE = "dsmListenerQueue";
  private static final long RECEIVE_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(10);

  private static EmbeddedActiveMQBroker broker;
  private static Connection connection;

  @BeforeAll
  static void startBroker() throws Exception {
    broker = new EmbeddedActiveMQBroker();
    broker.start();
    connection = broker.createConnectionFactory().createConnection();
    connection.start();
  }

  @BeforeEach
  void clearWriters() {
    writer.clear();
    dsmWriter.clear();
  }

  @AfterAll
  static void stopBroker() throws Exception {
    connection.close();
    broker.stop();
  }

  @Test
  void queueProduceAndConsumeLinksCheckpoints() throws Exception {
    Session producerSession = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
    MessageProducer producer = producerSession.createProducer(producerSession.createQueue(QUEUE));
    Session consumerSession = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
    MessageConsumer consumer = consumerSession.createConsumer(consumerSession.createQueue(QUEUE));
    TextMessage message = producerSession.createTextMessage(MESSAGE_TEXT);

    try {
      producer.send(message);
      TextMessage received = (TextMessage) consumer.receive(RECEIVE_TIMEOUT_MILLIS);
      consumer.receiveNoWait();

      assertNotNull(received);
      assertEquals(MESSAGE_TEXT, received.getText());
      assertNotNull(received.getStringProperty(PATHWAY_PROPERTY));
      writer.waitForTraces(2);

      StatsGroup outbound = awaitGroup("type:activemq", "direction:out", "topic:" + QUEUE);
      StatsGroup inbound = awaitGroup("type:activemq", "direction:in", "topic:" + QUEUE);
      assertEquals(0L, outbound.getParentHash());
      assertEquals(outbound.getHash(), inbound.getParentHash());
    } finally {
      producerSession.close();
      consumerSession.close();
    }
  }

  @Test
  void ibmMqMessagesKeepCheckpointsWithoutPathwayProperties() throws Exception {
    Session producerSession = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
    MessageProducer producer =
        producerSession.createProducer(producerSession.createQueue(IBM_QUEUE));
    Session consumerSession = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
    MessageConsumer consumer =
        consumerSession.createConsumer(consumerSession.createQueue(IBM_QUEUE));
    TextMessage message = new IbmMqTextMessage();
    message.setText(MESSAGE_TEXT);

    try {
      producer.send(message);
      TextMessage received = (TextMessage) consumer.receive(RECEIVE_TIMEOUT_MILLIS);
      consumer.receiveNoWait();

      assertNotNull(received);
      assertNull(received.getStringProperty(PATHWAY_PROPERTY));
      writer.waitForTraces(2);

      StatsGroup outbound = awaitGroup("type:ibmmq", "direction:out", "topic:" + IBM_QUEUE);
      StatsGroup inbound = awaitGroup("direction:in", "topic:" + IBM_QUEUE);
      assertEquals(0L, outbound.getParentHash());
      assertEquals(0L, inbound.getParentHash());
    } finally {
      producerSession.close();
      consumerSession.close();
    }
  }

  @Test
  void disabledPropagationQueueRecordsCheckpointsWithoutProperties() throws Exception {
    Session producerSession = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
    MessageProducer producer =
        producerSession.createProducer(producerSession.createQueue(DISABLED_QUEUE));
    Session consumerSession = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
    MessageConsumer consumer =
        consumerSession.createConsumer(consumerSession.createQueue(DISABLED_QUEUE));
    TextMessage message = producerSession.createTextMessage(MESSAGE_TEXT);

    try {
      producer.send(message);
      TextMessage received = (TextMessage) consumer.receive(RECEIVE_TIMEOUT_MILLIS);
      consumer.receiveNoWait();

      assertNotNull(received);
      assertNull(received.getStringProperty(PATHWAY_PROPERTY));
      writer.waitForTraces(2);

      StatsGroup outbound = awaitGroup("type:activemq", "direction:out", "topic:" + DISABLED_QUEUE);
      StatsGroup inbound = awaitGroup("direction:in", "topic:" + DISABLED_QUEUE);
      assertEquals(0L, outbound.getParentHash());
      assertEquals(0L, inbound.getParentHash());
    } finally {
      producerSession.close();
      consumerSession.close();
    }
  }

  @Test
  void listenerDeliveryRecordsInboundCheckpoint() throws Exception {
    Session producerSession = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
    MessageProducer producer =
        producerSession.createProducer(producerSession.createQueue(LISTENER_QUEUE));
    Session consumerSession = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
    MessageConsumer consumer =
        consumerSession.createConsumer(consumerSession.createQueue(LISTENER_QUEUE));
    CountDownLatch delivered = new CountDownLatch(1);
    consumer.setMessageListener(message -> delivered.countDown());
    TextMessage message = producerSession.createTextMessage(MESSAGE_TEXT);

    try {
      producer.send(message);
      assertTrue(delivered.await(RECEIVE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS));
      writer.waitForTraces(2);

      StatsGroup outbound = awaitGroup("type:activemq", "direction:out", "topic:" + LISTENER_QUEUE);
      StatsGroup inbound = awaitGroup("direction:in", "topic:" + LISTENER_QUEUE);
      assertEquals(0L, outbound.getParentHash());
      assertEquals(outbound.getHash(), inbound.getParentHash());
    } finally {
      producerSession.close();
      consumerSession.close();
    }
  }

  @ParameterizedTest
  @MethodSource("messageTechnologyArguments")
  void detectsMessageTechnology(Message message, String expectedTechnology) {
    assertEquals(expectedTechnology, JMSDecorator.messageTechnology(message));
  }

  static Stream<Arguments> messageTechnologyArguments() {
    return Stream.of(
        arguments(new ActiveMQTextMessage(), "activemq"),
        arguments(new IbmMqTextMessage(), "ibmmq"),
        arguments(new ArtemisTextMessage(), "artemis"),
        arguments(new SqsTextMessage(), "sqs"),
        arguments(new OtherBrokerTextMessage(), "jms"));
  }

  private static StatsGroup awaitGroup(String... tags) throws InterruptedException {
    long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(10);
    while (System.currentTimeMillis() < deadline) {
      for (StatsGroup group : dsmWriter.getGroups()) {
        if (Boolean.TRUE.equals(group.getTags().hasAllTags(tags))) {
          return group;
        }
      }
      Thread.sleep(20);
    }
    throw new AssertionError(
        "No stats group with tags " + String.join(", ", tags) + " was written");
  }

  static final class OtherBrokerTextMessage extends ActiveMQTextMessage {}
}

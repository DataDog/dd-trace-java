import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ibm.fake.FakeIbmMqTextMessage;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.agent.test.datastreams.DataStreamsRemoteConfigTracer;
import datadog.trace.test.junit.utils.config.WithConfig;
import datadog.trace.test.util.PollingConditions;
import java.util.concurrent.TimeUnit;
import javax.jms.Connection;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.MessageProducer;
import javax.jms.Queue;
import javax.jms.Session;
import javax.jms.TextMessage;
import org.apache.activemq.junit.EmbeddedActiveMQBroker;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Data Streams off at startup, then toggled at runtime the way remote config does. Covers the live
 * config gating of JMS checkpoints, not propagator registration: JMS never injects the pathway, so
 * only stats are checked.
 */
@WithConfig(key = "data.streams.enabled", value = "false")
class JMSDataStreamsRemoteConfigForkedTest extends AbstractInstrumentationTest {
  private static final String QUEUE = "dsmremoteconfig";

  private static DataStreamsRemoteConfigTracer dataStreams;

  private static EmbeddedActiveMQBroker broker;
  private static Connection connection;

  /** Installs a tracer with a recording DSM, since AbstractInstrumentationTest has none. */
  @BeforeAll
  static void installDataStreamsTracer() {
    dataStreams = DataStreamsRemoteConfigTracer.install(tracer, writer);
    tracer = dataStreams.tracer();
  }

  @BeforeAll
  static void startBroker() throws Exception {
    broker = new EmbeddedActiveMQBroker();
    broker.start();
    connection = broker.createConnectionFactory().createConnection();
    connection.start();
  }

  @AfterAll
  static void stopBroker() throws Exception {
    if (connection != null) {
      connection.close();
    }
    if (broker != null) {
      broker.stop();
    }
  }

  @BeforeEach
  void clearDataStreams() {
    dataStreams.clear();
  }

  @AfterEach
  void disableDataStreams() throws Exception {
    dataStreams.toggleDataStreams(false);
  }

  @Test
  void dataStreamsFollowsTheLiveConfigForClientsCreatedWhileItWasOff() throws Exception {
    Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
    try {
      Queue queue = session.createQueue(QUEUE);
      MessageProducer producer = session.createProducer(queue);
      MessageConsumer consumer = session.createConsumer(queue);

      // data streams is off
      sendAndReceive(producer, consumer, "off");
      assertEquals(0, dataStreams.settledGroupCount());

      // remote config enables data streams
      dataStreams.toggleDataStreams(true);
      sendAndReceive(producer, consumer, "on");
      new PollingConditions(5)
          .eventually(() -> {
            assertTrue(dataStreams.hasGroup("direction:out", "topic:" + QUEUE, "type:ibmmq"));
            assertTrue(dataStreams.hasGroup("direction:in", "topic:" + QUEUE, "type:ibmmq"));
          });

      // remote config disables data streams again
      dataStreams.toggleDataStreams(false);
      int groupsBeforeOffAgain = dataStreams.settledGroupCount();
      sendAndReceive(producer, consumer, "off again");
      // Stats stop too: no new group is written once DSM is off
      assertEquals(groupsBeforeOffAgain, dataStreams.settledGroupCount());
    } finally {
      session.close();
    }
  }

  /** Sends one message and receives it back. */
  private static void sendAndReceive(
      MessageProducer producer, MessageConsumer consumer, String text) throws Exception {
    producer.send(new FakeIbmMqTextMessage(text));
    Message received = consumer.receive(TimeUnit.SECONDS.toMillis(5));
    assertTrue(received instanceof FakeIbmMqTextMessage);
    assertEquals(text, ((TextMessage) received).getText());
  }
}

package datadog.trace.instrumentation.jms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.core.datastreams.StatsGroup;
import datadog.trace.test.junit.utils.config.WithConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.jms.Connection;
import javax.jms.MessageConsumer;
import javax.jms.MessageProducer;
import javax.jms.Session;
import javax.jms.TextMessage;
import org.apache.activemq.junit.EmbeddedActiveMQBroker;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@WithConfig(key = "data.streams.enabled", value = "true")
@WithConfig(key = "jms.legacy.tracing.enabled", value = "false")
public final class JmsTimeInQueueDataStreamsForkedTest extends AbstractInstrumentationTest {
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

  @ParameterizedTest
  @ValueSource(ints = {Session.CLIENT_ACKNOWLEDGE, Session.SESSION_TRANSACTED})
  void receiveKeepsEachMessagePathwayWhenBrokerSpanIsShared(int acknowledgeMode) throws Exception {
    String queue = "tiqReceive" + acknowledgeMode;
    String upstream = "tiqUpstream" + acknowledgeMode;
    Session consumerSession =
        connection.createSession(acknowledgeMode == Session.SESSION_TRANSACTED, acknowledgeMode);
    MessageConsumer consumer = consumerSession.createConsumer(consumerSession.createQueue(queue));
    try {
      sendFromTwoUpstreams(queue, upstream);

      TextMessage first = (TextMessage) consumer.receive(RECEIVE_TIMEOUT_MILLIS);
      TextMessage second = (TextMessage) consumer.receive(RECEIVE_TIMEOUT_MILLIS);
      assertNotNull(first);
      assertNotNull(second);
      if (acknowledgeMode == Session.SESSION_TRANSACTED) {
        consumerSession.commit();
      } else {
        second.acknowledge();
      }

      assertInboundParentsMatchEachProducer(queue, upstream);
    } finally {
      consumer.close();
      consumerSession.close();
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {Session.CLIENT_ACKNOWLEDGE, Session.SESSION_TRANSACTED})
  void listenerKeepsEachMessagePathwayWhenBrokerSpanIsShared(int acknowledgeMode) throws Exception {
    String queue = "tiqListener" + acknowledgeMode;
    String upstream = "tiqListenerUpstream" + acknowledgeMode;
    Session consumerSession =
        connection.createSession(acknowledgeMode == Session.SESSION_TRANSACTED, acknowledgeMode);
    MessageConsumer consumer = consumerSession.createConsumer(consumerSession.createQueue(queue));
    CountDownLatch delivered = new CountDownLatch(2);
    consumer.setMessageListener(message -> delivered.countDown());
    try {
      sendFromTwoUpstreams(queue, upstream);
      assertTrue(delivered.await(RECEIVE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS));

      assertInboundParentsMatchEachProducer(queue, upstream);
    } finally {
      consumer.close();
      consumerSession.close();
    }
  }

  private static void sendFromTwoUpstreams(String queue, String upstream) throws Exception {
    Session producerSession = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
    MessageProducer producer = producerSession.createProducer(producerSession.createQueue(queue));
    MessageProducer upstreamProducer =
        producerSession.createProducer(producerSession.createQueue(upstream));
    Session upstreamSession = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
    MessageConsumer upstreamConsumer =
        upstreamSession.createConsumer(upstreamSession.createQueue(upstream));
    try {
      producer.send(producerSession.createTextMessage("direct"));
      upstreamProducer.send(producerSession.createTextMessage("relayed"));
      assertNotNull(upstreamConsumer.receive(RECEIVE_TIMEOUT_MILLIS));
      producer.send(producerSession.createTextMessage("forwarded"));
      upstreamConsumer.receiveNoWait();
    } finally {
      upstreamConsumer.close();
      upstreamSession.close();
      producerSession.close();
    }
  }

  private static void assertInboundParentsMatchEachProducer(String queue, String upstream)
      throws InterruptedException {
    StatsGroup upstreamInbound = awaitGroups(1, "direction:in", "topic:" + upstream).get(0);
    List<StatsGroup> outbound = awaitGroups(2, "direction:out", "topic:" + queue);
    List<StatsGroup> inbound = awaitGroups(2, "direction:in", "topic:" + queue);

    StatsGroup direct = outbound.get(0).getParentHash() == 0L ? outbound.get(0) : outbound.get(1);
    StatsGroup forwarded = direct == outbound.get(0) ? outbound.get(1) : outbound.get(0);
    assertEquals(0L, direct.getParentHash());
    assertEquals(upstreamInbound.getHash(), forwarded.getParentHash());
    assertNotEquals(direct.getHash(), forwarded.getHash());

    List<Long> inboundParents = new ArrayList<>();
    for (StatsGroup group : inbound) {
      inboundParents.add(group.getParentHash());
    }
    assertTrue(inboundParents.contains(direct.getHash()), "inbound parents " + inboundParents);
    assertTrue(inboundParents.contains(forwarded.getHash()), "inbound parents " + inboundParents);
  }

  private static List<StatsGroup> awaitGroups(int count, String... tags)
      throws InterruptedException {
    long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(10);
    List<StatsGroup> matching = new ArrayList<>();
    while (System.currentTimeMillis() < deadline) {
      matching.clear();
      for (StatsGroup group : dsmWriter.getGroups()) {
        if (Boolean.TRUE.equals(group.getTags().hasAllTags(tags)) && !contains(matching, group)) {
          matching.add(group);
        }
      }
      if (matching.size() >= count) {
        return matching;
      }
      Thread.sleep(20);
    }
    throw new AssertionError(
        "Expected "
            + count
            + " stats groups with tags "
            + String.join(", ", tags)
            + ": "
            + matching);
  }

  private static boolean contains(List<StatsGroup> groups, StatsGroup candidate) {
    for (StatsGroup group : groups) {
      if (group.getHash() == candidate.getHash()
          && group.getParentHash() == candidate.getParentHash()) {
        return true;
      }
    }
    return false;
  }
}

import datadog.trace.agent.test.naming.VersionedNamingTestBase
import datadog.trace.api.Config
import datadog.trace.core.datastreams.StatsGroup
import org.apache.activemq.junit.EmbeddedActiveMQBroker
import spock.lang.Shared
import spock.util.concurrent.PollingConditions

import javax.jms.Connection
import javax.jms.MessageConsumer
import javax.jms.MessageProducer
import javax.jms.Session
import javax.jms.TextMessage

import java.util.concurrent.TimeUnit

abstract class JmsDataStreamsTestBase extends VersionedNamingTestBase {
  static final String PATHWAY_PROPERTY = "dd__dash__pathway__dash__ctx__dash__base64"

  @Shared
  EmbeddedActiveMQBroker broker = new EmbeddedActiveMQBroker()

  @Shared
  Connection connection

  @Shared
  String messageText = "a message"

  def setupSpec() {
    broker.start()
    connection = broker.createConnectionFactory().createConnection()
    connection.start()
  }

  def cleanupSpec() {
    broker.stop()
  }

  @Override
  protected boolean isDataStreamsEnabled() {
    return true
  }

  @Override
  protected long dataStreamsBucketDuration() {
    TimeUnit.MILLISECONDS.toNanos(250)
  }

  @Override
  String operation() {
    null
  }

  @Override
  int version() {
    0
  }

  @Override
  String service() {
    Config.get().getServiceName()
  }

  def "produce and consume on #queueName links checkpoints through message properties"() {
    setup:
    Session producerSession = connection.createSession(false, Session.AUTO_ACKNOWLEDGE)
    MessageProducer producer = producerSession.createProducer(producerSession.createQueue(queueName))
    Session consumerSession = connection.createSession(false, Session.AUTO_ACKNOWLEDGE)
    MessageConsumer consumer = consumerSession.createConsumer(consumerSession.createQueue(queueName))
    TextMessage message = producerSession.createTextMessage(messageText)

    when:
    producer.send(message)
    TextMessage received = consumer.receive(10000)

    then:
    received != null
    received.text == messageText
    received.getStringProperty(PATHWAY_PROPERTY) != null
    TEST_WRITER.waitForTraces(2)

    def conditions = new PollingConditions(timeout: 5)
    conditions.eventually {
      List<StatsGroup> groups = TEST_DATA_STREAMS_WRITER.groups
      StatsGroup outbound = groups.find { it.tags.direction == "direction:out" }
      StatsGroup inbound = groups.find { it.tags.direction == "direction:in" }
      assert outbound != null
      assert inbound != null
      verifyAll(outbound.tags) {
        hasAllTags(["type:activemq", "direction:out", "topic:" + queueName] as String[])
      }
      verifyAll(inbound.tags) {
        hasAllTags(["type:activemq", "direction:in", "topic:" + queueName] as String[])
      }
      assert outbound.parentHash == 0L
      assert inbound.parentHash == outbound.hash
    }

    cleanup:
    producerSession.close()
    consumerSession.close()

    where:
    queueName << ["dsmQueue"]
  }

  def "ibm mq messages keep checkpoints without pathway properties"() {
    setup:
    Session producerSession = connection.createSession(false, Session.AUTO_ACKNOWLEDGE)
    MessageProducer producer = producerSession.createProducer(producerSession.createQueue("dsmIbmQueue"))
    Session consumerSession = connection.createSession(false, Session.AUTO_ACKNOWLEDGE)
    MessageConsumer consumer = consumerSession.createConsumer(consumerSession.createQueue("dsmIbmQueue"))
    TextMessage message = new com.ibm.msgclient.jms.IbmMqTextMessage()
    message.text = messageText

    when:
    producer.send(message)
    TextMessage received = consumer.receive(10000)

    then:
    received != null
    received.getStringProperty(PATHWAY_PROPERTY) == null
    TEST_WRITER.waitForTraces(2)

    def conditions = new PollingConditions(timeout: 5)
    conditions.eventually {
      List<StatsGroup> groups = TEST_DATA_STREAMS_WRITER.groups
      StatsGroup outbound = groups.find {
        it.tags.direction == "direction:out" && it.tags.type == "type:ibmmq"
      }
      assert outbound != null
      verifyAll(outbound.tags) {
        hasAllTags(["type:ibmmq", "direction:out", "topic:dsmIbmQueue"] as String[])
      }
      assert outbound.parentHash == 0L
    }

    cleanup:
    producerSession.close()
    consumerSession.close()
  }
}

class JmsDataStreamsTest extends JmsDataStreamsTestBase {}

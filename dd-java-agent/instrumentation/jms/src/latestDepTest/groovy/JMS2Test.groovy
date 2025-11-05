import com.google.common.io.Files
import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.agent.test.asserts.ListWriterAssert
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.core.DDSpan
import org.hornetq.api.core.TransportConfiguration
import org.hornetq.api.core.client.HornetQClient
import org.hornetq.api.jms.HornetQJMSClient
import org.hornetq.api.jms.JMSFactoryType
import org.hornetq.core.config.Configuration
import org.hornetq.core.config.CoreQueueConfiguration
import org.hornetq.core.config.impl.ConfigurationImpl
import org.hornetq.core.remoting.impl.invm.InVMAcceptorFactory
import org.hornetq.core.remoting.impl.invm.InVMConnectorFactory
import org.hornetq.core.remoting.impl.netty.NettyAcceptorFactory
import org.hornetq.core.server.HornetQServer
import org.hornetq.core.server.HornetQServers
import org.hornetq.jms.client.HornetQTextMessage
import spock.lang.Shared

import javax.jms.Message
import javax.jms.MessageListener
import javax.jms.Session
import javax.jms.TextMessage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

class JMS2Test extends InstrumentationSpecification {
  @Shared
  HornetQServer server
  @Shared
  String messageText = "a message"
  @Shared
  Session session

  HornetQTextMessage message = session.createTextMessage(messageText)

  def setupSpec() {
    def tempDir = Files.createTempDir()
    tempDir.deleteOnExit()

    Configuration config = new ConfigurationImpl()
    config.bindingsDirectory = tempDir.path
    config.journalDirectory = tempDir.path
    config.createBindingsDir = false
    config.createJournalDir = false
    config.securityEnabled = false
    config.persistenceEnabled = false
    config.setQueueConfigurations([new CoreQueueConfiguration("someQueue", "someQueue", null, true)])
    config.setAcceptorConfigurations([
      new TransportConfiguration(NettyAcceptorFactory.name),
      new TransportConfiguration(InVMAcceptorFactory.name)
    ].toSet())

    server = HornetQServers.newHornetQServer(config)
    server.start()

    def serverLocator = HornetQClient.createServerLocatorWithoutHA(new TransportConfiguration(InVMConnectorFactory.name))
    def sf = serverLocator.createSessionFactory()
    def clientSession = sf.createSession(false, false, false)
    clientSession.createQueue("jms.queue.someQueue", "jms.queue.someQueue", true)
    clientSession.createQueue("jms.topic.someTopic", "jms.topic.someTopic", true)
    clientSession.close()
    sf.close()
    serverLocator.close()

    def connectionFactory = HornetQJMSClient.createConnectionFactoryWithoutHA(JMSFactoryType.CF,
      new TransportConfiguration(InVMConnectorFactory.name))

    def connection = connectionFactory.createConnection()
    connection.start()
    session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE)
    session.run()
  }

  def cleanupSpec() {
    server.stop()
  }

  def "sending a message to #jmsResourceName generates spans"() {
    setup:
    def producer = session.createProducer(destination)
    def consumer = session.createConsumer(destination)

    producer.send(message)

    TextMessage receivedMessage = consumer.receive()
    // required to finish auto-acknowledged spans
    consumer.receiveNoWait()

    expect:
    receivedMessage.text == messageText
    assertTraces(2) {
      producerTrace(it, jmsResourceName)
      consumerTrace(it, jmsResourceName, trace(0)[0])
    }

    cleanup:
    producer.close()
    consumer.close()

    where:
    destination                      | jmsResourceName
    session.createQueue("someQueue") | "Queue someQueue"
    session.createTopic("someTopic") | "Topic someTopic"
    session.createTemporaryQueue()   | "Temporary Queue"
    session.createTemporaryTopic()   | "Temporary Topic"
  }

  def "sending to a MessageListener on #jmsResourceName generates a span"() {
    setup:
    def lock = new CountDownLatch(1)
    def messageRef = new AtomicReference<TextMessage>()
    def producer = session.createProducer(destination)
    def consumer = session.createConsumer(destination)
    consumer.setMessageListener new MessageListener() {
        @Override
        void onMessage(Message message) {
          lock.await() // ensure the producer trace is reported first.
          messageRef.set(message)
        }
      }

    producer.send(message)
    lock.countDown()

    expect:
    assertTraces(2) {
      producerTrace(it, jmsResourceName)
      consumerTrace(it, jmsResourceName, trace(0)[0])
    }
    // This check needs to go after all traces have been accounted for
    messageRef.get().text == messageText

    cleanup:
    producer.close()
    consumer.close()

    where:
    destination                      | jmsResourceName
    session.createQueue("someQueue") | "Queue someQueue"
    session.createTopic("someTopic") | "Topic someTopic"
    session.createTemporaryQueue()   | "Temporary Queue"
    session.createTemporaryTopic()   | "Temporary Topic"
  }

  def "failing to receive message with receiveNoWait on #jmsResourceName works"() {
    setup:
    def consumer = session.createConsumer(destination)

    // Receive with timeout
    TextMessage receivedMessage = consumer.receiveNoWait()
    // required to finish auto-acknowledged spans
    consumer.receiveNoWait()

    expect:
    receivedMessage == null
    assertTraces(0) {}

    cleanup:
    consumer.close()

    where:
    destination                      | jmsResourceName
    session.createQueue("someQueue") | "Queue someQueue"
    session.createTopic("someTopic") | "Topic someTopic"
  }

  def "failing to receive message with wait(timeout) on #jmsResourceName works"() {
    setup:
    def consumer = session.createConsumer(destination)

    // Receive with timeout
    TextMessage receivedMessage = consumer.receive(1)
    // required to finish auto-acknowledged spans
    consumer.receiveNoWait()

    expect:
    receivedMessage == null
    assertTraces(0) {}

    cleanup:
    consumer.close()

    where:
    destination                      | jmsResourceName
    session.createQueue("someQueue") | "Queue someQueue"
    session.createTopic("someTopic") | "Topic someTopic"
  }

  def "sending a message with disabled timestamp generates spans without specific tag"() {
    setup:
    def producer = session.createProducer(session.createQueue("someQueue"))
    def consumer = session.createConsumer(session.createQueue("someQueue"))

    producer.setDisableMessageTimestamp(true)
    boolean isTimeStampDisabled = producer.getDisableMessageTimestamp()
    producer.send(message)

    consumer.receive()
    // required to finish auto-acknowledged spans
    consumer.receiveNoWait()

    expect:
    assertTraces(2) {
      producerTrace(it, "Queue someQueue")
      consumerTrace(it, "Queue someQueue", trace(0)[0], isTimeStampDisabled)
    }

    cleanup:
    producer.close()
    consumer.close()

  }

  static producerTrace(ListWriterAssert writer, String jmsResourceName) {
    writer.trace(1) {
      span {
        parent()
        serviceName "jms"
        operationName "jms.produce"
        resourceName "Produced for $jmsResourceName"
        spanType DDSpanTypes.MESSAGE_PRODUCER
        errored false

        tags {
          "$Tags.COMPONENT" "jms"
          "$Tags.SPAN_KIND" Tags.SPAN_KIND_PRODUCER
          defaultTagsNoPeerService()
        }
      }
    }
  }

  static consumerTrace(ListWriterAssert writer, String jmsResourceName, DDSpan parentSpan, boolean isTimestampDisabled = false) {
    writer.trace(1) {
      span {
        childOf parentSpan
        serviceName "jms"
        operationName "jms.consume"
        resourceName "Consumed from $jmsResourceName"
        spanType DDSpanTypes.MESSAGE_CONSUMER
        errored false

        tags {
          "${Tags.COMPONENT}" "jms"
          "${Tags.SPAN_KIND}" "consumer"
          if (!isTimestampDisabled) {
            "$InstrumentationTags.RECORD_QUEUE_TIME_MS" {it >= 0 }
          }
          defaultTagsNoPeerService(true)
        }
      }
    }
  }
}

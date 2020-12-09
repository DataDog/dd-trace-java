import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.agent.test.asserts.ListWriterAssert
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.core.DDSpan
import org.apache.activemq.ActiveMQConnectionFactory
import org.apache.activemq.ActiveMQMessageConsumer
import org.apache.activemq.command.ActiveMQTextMessage
import org.apache.activemq.junit.EmbeddedActiveMQBroker
import spock.lang.Shared

import javax.jms.Connection
import javax.jms.Message
import javax.jms.MessageListener
import javax.jms.Session
import javax.jms.TextMessage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

class JMS1Test extends AgentTestRunner {
  @Shared
  EmbeddedActiveMQBroker broker = new EmbeddedActiveMQBroker()
  @Shared
  String messageText = "a message"
  @Shared
  Session session

  ActiveMQTextMessage message = session.createTextMessage(messageText)

  def setupSpec() {
    broker.start()
    final ActiveMQConnectionFactory connectionFactory = broker.createConnectionFactory()

    final Connection connection = connectionFactory.createConnection()
    connection.start()
    session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE)
  }

  def cleanupSpec() {
    broker.stop()
  }

  def "sending a message to #jmsResourceName generates spans"() {
    setup:
    def producer = session.createProducer(destination)
    def consumer = session.createConsumer(destination)

    producer.send(message)

    TextMessage receivedMessage = consumer.receive()

    expect:
    receivedMessage.text == messageText
    assertTraces(2) {
      producerTrace(it, jmsResourceName)
      consumerTrace(it, jmsResourceName, false, ActiveMQMessageConsumer, trace(0)[0])
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
      consumerTrace(it, jmsResourceName, true, consumer.messageListener.class, trace(0)[0])
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

    expect:
    receivedMessage == null
    assertTraces(1) {
      trace(1) { // Consumer trace
        span {
          parent()
          serviceName "jms"
          operationName "jms.consume"
          resourceName "JMS receiveNoWait"
          spanType DDSpanTypes.MESSAGE_CONSUMER
          errored false

          tags {
            "$Tags.COMPONENT" "jms"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CONSUMER
            defaultTags()
          }
        }
      }
    }

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
    TextMessage receivedMessage = consumer.receive(100)

    expect:
    receivedMessage == null
    assertTraces(1) {
      trace(1) { // Consumer trace
        span {
          parent()
          serviceName "jms"
          operationName "jms.consume"
          resourceName "JMS receive"
          spanType DDSpanTypes.MESSAGE_CONSUMER
          errored false

          tags {
            "$Tags.COMPONENT" "jms"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CONSUMER
            defaultTags()
          }
        }
      }
    }

    cleanup:
    consumer.close()

    where:
    destination                      | jmsResourceName
    session.createQueue("someQueue") | "Queue someQueue"
    session.createTopic("someTopic") | "Topic someTopic"
  }

  def "sending a read-only message to #jmsResourceName fails"() {
    setup:
    def producer = session.createProducer(destination)
    def consumer = session.createConsumer(destination)

    expect:
    !message.isReadOnlyProperties()

    when:
    message.setReadOnlyProperties(true)
    and:
    producer.send(message)

    TextMessage receivedMessage = consumer.receive()

    then:
    receivedMessage.text == messageText

    // This will result in a logged failure because we tried to
    // write properties in MessagePropertyTextMap when readOnlyProperties = true.
    // The consumer span will also not be linked to the parent.
    assertTraces(2) {
      producerTrace(it, jmsResourceName)
      trace(1) { // Consumer trace
        span {
          parent()
          serviceName "jms"
          operationName "jms.consume"
          resourceName "Consumed from $jmsResourceName"
          spanType DDSpanTypes.MESSAGE_CONSUMER
          errored false

          tags {
            "$Tags.COMPONENT" "jms"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CONSUMER
            "$InstrumentationTags.RECORD_QUEUE_TIME_MS" {it >= 0 }
            defaultTags()
          }
        }
      }
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

  def "sending a message with disabled timestamp generates spans without specific tag"() {
    setup:
    def producer = session.createProducer(session.createQueue("someQueue"))
    def consumer = session.createConsumer(session.createQueue("someQueue"))

    producer.send(message)
    producer.setDisableMessageTimestamp(true)
    consumer.receive()

    expect:
    assertTraces(2) {
      producerTrace(it, "Queue someQueue")
      consumerTrace(it, "Queue someQueue", false, ActiveMQMessageConsumer, trace(0)[0])
    }

    cleanup:
    producer.close()
    consumer.close()

  }

  static producerTrace(ListWriterAssert writer, String jmsResourceName) {
    writer.trace(1) {
      span {
        serviceName "jms"
        operationName "jms.produce"
        resourceName "Produced for $jmsResourceName"
        spanType DDSpanTypes.MESSAGE_PRODUCER
        errored false
        parent()

        tags {
          "$Tags.COMPONENT" "jms"
          "$Tags.SPAN_KIND" Tags.SPAN_KIND_PRODUCER
          defaultTags()
        }
      }
    }
  }

  static consumerTrace(ListWriterAssert writer, String jmsResourceName, boolean messageListener, Class origin, DDSpan parentSpan) {
    writer.trace(1) {
      span {
        serviceName "jms"
        if (messageListener) {
          operationName "jms.onMessage"
          resourceName "Received from $jmsResourceName"
        } else {
          operationName "jms.consume"
          resourceName "Consumed from $jmsResourceName"
        }
        spanType DDSpanTypes.MESSAGE_CONSUMER
        errored false
        childOf parentSpan

        tags {
          "$Tags.COMPONENT" "jms"
          "$Tags.SPAN_KIND" Tags.SPAN_KIND_CONSUMER
          if (!messageListener && "$InstrumentationTags.RECORD_QUEUE_TIME_MS") {
            "$InstrumentationTags.RECORD_QUEUE_TIME_MS" {it >= 0 }
          }
          defaultTags(true)
        }
      }
    }
  }
}

import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.agent.test.asserts.ListWriterAssert
import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.api.DDSpanTypes
import datadog.trace.api.Trace
import datadog.trace.api.config.TraceInstrumentationConfig
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.core.DDSpan
import org.apache.activemq.ActiveMQConnectionFactory
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

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.propagate
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan
import static datadog.trace.instrumentation.jms.JMSDecorator.JMS_PRODUCE
import static datadog.trace.instrumentation.jms.JMSDecorator.PRODUCER_DECORATE
import static datadog.trace.instrumentation.jms.MessageInjectAdapter.SETTER

class JMS1Test extends AgentTestRunner {
  @Shared
  EmbeddedActiveMQBroker broker = new EmbeddedActiveMQBroker()
  @Shared
  String messageText = "a message"
  @Shared
  Session session

  @Shared
  Connection connection

  ActiveMQTextMessage message = session.createTextMessage(messageText)

  def setupSpec() {
    broker.start()
    final ActiveMQConnectionFactory connectionFactory = broker.createConnectionFactory()

    connection = connectionFactory.createConnection()
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

  def "receiving a message from #jmsResourceName in a transacted session"() {
    setup:
    def transactedSession = connection.createSession(true, Session.SESSION_TRANSACTED)
    def producer = session.createProducer(destination)
    def consumer = transactedSession.createConsumer(destination)

    producer.send(message)

    TextMessage receivedMessage = consumer.receive()
    transactedSession.commit()

    expect:
    receivedMessage.text == messageText
    assertTraces(2) {
      producerTrace(it, jmsResourceName)
      consumerTrace(it, jmsResourceName, trace(0)[0])
    }

    cleanup:
    producer.close()
    consumer.close()
    transactedSession.close()

    where:
    destination                      | jmsResourceName
    session.createQueue("someQueue") | "Queue someQueue"
    session.createTopic("someTopic") | "Topic someTopic"
    session.createTemporaryQueue()   | "Temporary Queue"
    session.createTemporaryTopic()   | "Temporary Topic"
  }

  def "receiving a message from #jmsResourceName with manual acknowledgement"() {
    setup:
    def session = connection.createSession(false, Session.CLIENT_ACKNOWLEDGE)
    def producer = session.createProducer(destination)
    def consumer = session.createConsumer(destination)

    producer.send(message)

    TextMessage receivedMessage = consumer.receive()
    receivedMessage.acknowledge()

    expect:
    receivedMessage.text == messageText
    assertTraces(2) {
      producerTrace(it, jmsResourceName)
      consumerTrace(it, jmsResourceName, trace(0)[0])
    }

    cleanup:
    producer.close()
    consumer.close()
    session.close()

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
    TextMessage receivedMessage = consumer.receive(100)
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
    // required to finish auto-acknowledged spans
    consumer.receiveNoWait()

    then:
    receivedMessage.text == messageText

    // This will result in a logged failure because we tried to
    // write properties in MessagePropertyTextMap when readOnlyProperties = true.
    // The consumer span will also not be linked to the parent.
    assertTraces(2) {
      producerTrace(it, jmsResourceName)
      trace(1) {
        // Consumer trace
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
            "$InstrumentationTags.RECORD_QUEUE_TIME_MS" { it >= 0 }
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

    producer.setDisableMessageTimestamp(true)
    producer.send(message)

    boolean isTimeStampDisabled = producer.getDisableMessageTimestamp()
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

  def "traceable work between two receive calls has jms.consume parent"() {
    setup:
    def producer = session.createProducer(destination)
    def consumer = session.createConsumer(destination)

    producer.send(message)

    TextMessage receivedMessage = consumer.receive()
    doStuff()
    // required to finish auto-acknowledged spans
    consumer.receiveNoWait()

    expect:
    receivedMessage.text == messageText
    assertTraces(2) {
      producerTrace(it, jmsResourceName)
      trace(2) {
        consumerSpan(it, jmsResourceName, trace(0)[0])
        span {
          operationName "do.stuff"
          childOf(trace(1)[0])
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

  def "sending a message to #jmsResourceName with given disabled topic or queue disables propagation on producer side"() {
    setup:
    injectSysConfig(TraceInstrumentationConfig.JMS_PROPAGATION_DISABLED_TOPICS, topic)
    injectSysConfig(TraceInstrumentationConfig.JMS_PROPAGATION_DISABLED_QUEUES, queue)
    def producer = session.createProducer(destination)
    def consumer = session.createConsumer(destination)
    producer.send(message)
    TextMessage receivedMessage = consumer.receive()
    // required to finish auto-acknowledged spans
    consumer.receiveNoWait()
    expect:
    receivedMessage.text == messageText
    if (expected) {
      assertTraces(2) {
        producerTrace(it, jmsResourceName)
        consumerTrace(it, jmsResourceName, trace(0)[0])
      }
    } else {
      assertTraces(2) {
        producerTrace(it, jmsResourceName)
        trace(1) {
          span {
            parentId(0 as BigInteger)
            serviceName "jms"
            operationName "jms.consume"
            resourceName "Consumed from $jmsResourceName"
            spanType DDSpanTypes.MESSAGE_CONSUMER
            errored false
            tags {
              "$Tags.COMPONENT" "jms"
              "$Tags.SPAN_KIND" Tags.SPAN_KIND_CONSUMER
              "$InstrumentationTags.RECORD_QUEUE_TIME_MS" { it >= 0 }
              defaultTags()
            }
          }
        }
      }
    }
    cleanup:
    producer.close()
    consumer.close()
    where:
    destination                      | jmsResourceName   | queue       | topic       | expected
    session.createQueue("someQueue") | "Queue someQueue" | "someQueue" | "someTopic" | false
    session.createTopic("someTopic") | "Topic someTopic" | ""          | "someTopic" | false
    session.createTemporaryQueue()   | "Temporary Queue" | ""          | ""          | true
    session.createTemporaryTopic()   | "Temporary Topic" | "random"    | ""          | true
  }

  def "sending a message to #jmsResourceName with given disabled topic or queue disables propagation on consumer side"() {
    setup:
    injectSysConfig(TraceInstrumentationConfig.JMS_PROPAGATION_DISABLED_TOPICS, topic)
    injectSysConfig(TraceInstrumentationConfig.JMS_PROPAGATION_DISABLED_QUEUES, queue)
    def producer = session.createProducer(destination)
    def consumer = session.createConsumer(destination)
    producer.send(message)
    TextMessage receivedMessage = consumer.receive()
    // required to finish auto-acknowledged spans
    consumer.receiveNoWait()
    expect:
    receivedMessage.text == messageText
    if (expected) {
      assertTraces(2) {
        producerTrace(it, jmsResourceName)
        consumerTrace(it, jmsResourceName, trace(0)[0])
      }
    } else {
      assertTraces(2) {
        producerTrace(it, jmsResourceName)
        trace(1) {
          span {
            parentId(0 as BigInteger)
            serviceName "jms"
            operationName "jms.consume"
            resourceName "Consumed from $jmsResourceName"
            spanType DDSpanTypes.MESSAGE_CONSUMER
            errored false
            tags {
              "$Tags.COMPONENT" "jms"
              "$Tags.SPAN_KIND" Tags.SPAN_KIND_CONSUMER
              "$InstrumentationTags.RECORD_QUEUE_TIME_MS" { it >= 0 }
              defaultTags()
            }
          }
        }
      }
    }
    cleanup:
    producer.close()
    consumer.close()
    where:
    destination                      | jmsResourceName   | queue       | topic       | expected
    session.createQueue("someQueue") | "Queue someQueue" | "someQueue" | "someTopic" | false
    session.createTopic("someTopic") | "Topic someTopic" | ""          | "someTopic" | false
    session.createTemporaryQueue()   | "Temporary Queue" | ""          | ""          | true
    session.createTemporaryTopic()   | "Temporary Topic" | "random"    | ""          | true
  }


  static producerTrace(ListWriterAssert writer, String jmsResourceName) {
    writer.trace(1) {
      producerSpan(it, jmsResourceName)
    }
  }

  static producerSpan(TraceAssert traceAssert, String jmsResourceName) {
    return traceAssert.span {
      serviceName "jms"
      operationName "jms.produce"
      resourceName "Produced for $jmsResourceName"
      spanType DDSpanTypes.MESSAGE_PRODUCER
      errored false
      measured true
      parent()

      tags {
        "$Tags.COMPONENT" "jms"
        "$Tags.SPAN_KIND" Tags.SPAN_KIND_PRODUCER
        defaultTags()
      }
    }
  }

  static consumerTrace(ListWriterAssert writer, String jmsResourceName, DDSpan parentSpan, boolean isTimestampDisabled = false) {
    writer.trace(1) {
      consumerSpan(it, jmsResourceName, parentSpan, isTimestampDisabled)
    }
  }

  static consumerSpan(TraceAssert traceAssert, String jmsResourceName, DDSpan parentSpan, boolean isTimestampDisabled = false) {
    return traceAssert.span {
      serviceName "jms"
      operationName "jms.consume"
      resourceName "Consumed from $jmsResourceName"
      spanType DDSpanTypes.MESSAGE_CONSUMER
      errored false
      measured true
      childOf parentSpan

      tags {
        "$Tags.COMPONENT" "jms"
        "$Tags.SPAN_KIND" Tags.SPAN_KIND_CONSUMER
        if (!isTimestampDisabled) {
          "$InstrumentationTags.RECORD_QUEUE_TIME_MS" { it >= 0 }
        }
        defaultTags(true)
      }
    }
  }

  @Trace(operationName = "do.stuff")
  def doStuff() {

  }
}

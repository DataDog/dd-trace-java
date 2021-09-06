import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.agent.test.asserts.ListWriterAssert
import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.api.DDSpanTypes
import datadog.trace.api.config.GeneralConfig
import datadog.trace.api.config.TraceInstrumentationConfig
import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.core.DDSpan
import org.apache.activemq.junit.EmbeddedActiveMQBroker
import spock.lang.Shared

import javax.jms.Connection
import javax.jms.Session
import javax.jms.TextMessage

class TimeInQueueForkedTest extends AgentTestRunner {
  @Shared
  EmbeddedActiveMQBroker broker = new EmbeddedActiveMQBroker()
  @Shared
  Connection connection
  @Shared
  Session session
  @Shared
  String messageText1 = "a message"
  @Shared
  String messageText2 = "another message"
  @Shared
  String messageText3 = "yet another message"
  @Shared
  String messageText4 = "another message again"
  @Shared
  String messageText5 = "just one more message"

  TextMessage message1 = session.createTextMessage(messageText1)
  TextMessage message2 = session.createTextMessage(messageText2)
  TextMessage message3 = session.createTextMessage(messageText3)
  TextMessage message4 = session.createTextMessage(messageText4)
  TextMessage message5 = session.createTextMessage(messageText5)

  @Override
  protected void configurePreAgent() {
    injectSysConfig(TraceInstrumentationConfig.JMS_LEGACY_TRACING_ENABLED, 'false')
    injectSysConfig(GeneralConfig.SERVICE_NAME, 'myService')
  }

  def setupSpec() {
    broker.start()
    connection = broker.createConnectionFactory().createConnection()
    connection.start()
    session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE)
  }

  def cleanupSpec() {
    broker.stop()
  }

  def "sending messages to #jmsResourceName generates time-in-queue spans"() {
    setup:
    def producerSession = connection.createSession(false, Session.AUTO_ACKNOWLEDGE)
    def producer = producerSession.createProducer(destination)
    def consumerSession = connection.createSession(false, Session.AUTO_ACKNOWLEDGE)
    def consumer = consumerSession.createConsumer(destination)

    when:
    producer.send(message1)
    producer.send(message2)
    producer.send(message3)
    producer.send(message4)
    producer.send(message5)

    def receivedMessage1 = consumer.receive()
    def receivedMessage2 = consumer.receive()
    def receivedMessage3 = consumer.receive()
    def receivedMessage4 = consumer.receive()
    def receivedMessage5 = consumer.receive()

    then:
    receivedMessage1.text == messageText1
    receivedMessage2.text == messageText2
    receivedMessage3.text == messageText3
    receivedMessage4.text == messageText4
    receivedMessage5.text == messageText5
    // only four consume traces will be finished at this point
    assertTraces(9) {
      producerTrace(it, jmsResourceName)
      producerTrace(it, jmsResourceName)
      producerTrace(it, jmsResourceName)
      producerTrace(it, jmsResourceName)
      producerTrace(it, jmsResourceName)
      consumerTrace(it, jmsResourceName, trace(0)[0])
      consumerTrace(it, jmsResourceName, trace(1)[0])
      consumerTrace(it, jmsResourceName, trace(2)[0])
      consumerTrace(it, jmsResourceName, trace(3)[0])
    }

    when:
    consumer.receiveNoWait()

    then:
    // now the last consume trace will also be finished
    assertTraces(10) {
      producerTrace(it, jmsResourceName)
      producerTrace(it, jmsResourceName)
      producerTrace(it, jmsResourceName)
      producerTrace(it, jmsResourceName)
      producerTrace(it, jmsResourceName)
      consumerTrace(it, jmsResourceName, trace(0)[0])
      consumerTrace(it, jmsResourceName, trace(1)[0])
      consumerTrace(it, jmsResourceName, trace(2)[0])
      consumerTrace(it, jmsResourceName, trace(3)[0])
      consumerTrace(it, jmsResourceName, trace(4)[0])
    }

    cleanup:
    producerSession.close()
    consumerSession.close()

    where:
    destination                              | jmsResourceName
    session.createQueue("someQueue") | "Queue someQueue"
    session.createTopic("someTopic") | "Topic someTopic"
    session.createTemporaryQueue()   | "Temporary Queue"
    session.createTemporaryTopic()   | "Temporary Topic"
  }

  def "sending messages to #jmsResourceName with manual acknowledgement generates time-in-queue spans"() {
    setup:
    def producerSession = connection.createSession(false, Session.AUTO_ACKNOWLEDGE)
    def producer = producerSession.createProducer(destination)
    def consumerSession = connection.createSession(false, Session.CLIENT_ACKNOWLEDGE)
    def consumer = consumerSession.createConsumer(destination)

    when:
    producer.send(message1)
    producer.send(message2)
    producer.send(message3)
    producer.send(message4)
    producer.send(message5)

    def receivedMessage1 = consumer.receive()
    def receivedMessage2 = consumer.receive()
    def receivedMessage3 = consumer.receive()
    receivedMessage2.acknowledge()
    def receivedMessage4 = consumer.receive()
    def receivedMessage5 = consumer.receive()

    then:
    receivedMessage1.text == messageText1
    receivedMessage2.text == messageText2
    receivedMessage3.text == messageText3
    receivedMessage4.text == messageText4
    receivedMessage5.text == messageText5
    // only three consume traces will be finished at this point
    assertTraces(6) {
      producerTrace(it, jmsResourceName)
      producerTrace(it, jmsResourceName)
      producerTrace(it, jmsResourceName)
      producerTrace(it, jmsResourceName)
      producerTrace(it, jmsResourceName)
      trace(4) {
        timeInQueueSpan(it, jmsResourceName, trace(0)[0])
        consumerSpan(it, jmsResourceName, trace(5)[0], false)
        consumerSpan(it, jmsResourceName, trace(5)[0], false)
        consumerSpan(it, jmsResourceName, trace(5)[0], false)
      }
    }

    when:
    receivedMessage5.acknowledge()

    then:
    // now the other consume traces will be finished
    assertTraces(7) {
      producerTrace(it, jmsResourceName)
      producerTrace(it, jmsResourceName)
      producerTrace(it, jmsResourceName)
      producerTrace(it, jmsResourceName)
      producerTrace(it, jmsResourceName)
      trace(4) {
        timeInQueueSpan(it, jmsResourceName, trace(0)[0])
        consumerSpan(it, jmsResourceName, trace(5)[0], false)
        consumerSpan(it, jmsResourceName, trace(5)[0], false)
        consumerSpan(it, jmsResourceName, trace(5)[0], false)
      }
      trace(3) {
        timeInQueueSpan(it, jmsResourceName, trace(3)[0])
        consumerSpan(it, jmsResourceName, trace(6)[0], false)
        consumerSpan(it, jmsResourceName, trace(6)[0], false)
      }
    }

    cleanup:
    producerSession.close()
    consumerSession.close()

    where:
    destination                              | jmsResourceName
    session.createQueue("someQueue") | "Queue someQueue"
    session.createTopic("someTopic") | "Topic someTopic"
    session.createTemporaryQueue()   | "Temporary Queue"
    session.createTemporaryTopic()   | "Temporary Topic"
  }

  def "sending messages to #jmsResourceName with transacted acknowledgement generates time-in-queue spans"() {
    setup:
    def producerSession = connection.createSession(false, Session.AUTO_ACKNOWLEDGE)
    def producer = producerSession.createProducer(destination)
    def consumerSession = connection.createSession(true, Session.SESSION_TRANSACTED)
    def consumer = consumerSession.createConsumer(destination)

    when:
    producer.send(message1)
    producer.send(message2)
    producer.send(message3)
    producer.send(message4)
    producer.send(message5)

    def receivedMessage1 = consumer.receive()
    def receivedMessage2 = consumer.receive()
    consumerSession.commit()
    def receivedMessage3 = consumer.receive()
    def receivedMessage4 = consumer.receive()
    def receivedMessage5 = consumer.receive()

    then:
    receivedMessage1.text == messageText1
    receivedMessage2.text == messageText2
    receivedMessage3.text == messageText3
    receivedMessage4.text == messageText4
    receivedMessage5.text == messageText5
    // only two consume traces will be finished at this point
    assertTraces(6) {
      producerTrace(it, jmsResourceName)
      producerTrace(it, jmsResourceName)
      producerTrace(it, jmsResourceName)
      producerTrace(it, jmsResourceName)
      producerTrace(it, jmsResourceName)
      trace(3) {
        timeInQueueSpan(it, jmsResourceName, trace(0)[0])
        consumerSpan(it, jmsResourceName, trace(5)[0], false)
        consumerSpan(it, jmsResourceName, trace(5)[0], false)
      }
    }

    when:
    consumerSession.commit()

    then:
    // now the other consume traces will be finished
    assertTraces(7) {
      producerTrace(it, jmsResourceName)
      producerTrace(it, jmsResourceName)
      producerTrace(it, jmsResourceName)
      producerTrace(it, jmsResourceName)
      producerTrace(it, jmsResourceName)
      trace(3) {
        timeInQueueSpan(it, jmsResourceName, trace(0)[0])
        consumerSpan(it, jmsResourceName, trace(5)[0], false)
        consumerSpan(it, jmsResourceName, trace(5)[0], false)
      }
      trace(4) {
        timeInQueueSpan(it, jmsResourceName, trace(2)[0])
        consumerSpan(it, jmsResourceName, trace(6)[0], false)
        consumerSpan(it, jmsResourceName, trace(6)[0], false)
        consumerSpan(it, jmsResourceName, trace(6)[0], false)
      }
    }

    cleanup:
    producerSession.close()
    consumerSession.close()

    where:
    destination                              | jmsResourceName
    session.createQueue("someQueue") | "Queue someQueue"
    session.createTopic("someTopic") | "Topic someTopic"
    session.createTemporaryQueue()   | "Temporary Queue"
    session.createTemporaryTopic()   | "Temporary Topic"
  }

  static producerTrace(ListWriterAssert writer, String jmsResourceName) {
    writer.trace(1) {
      producerSpan(it, jmsResourceName)
    }
  }

  static producerSpan(TraceAssert traceAssert, String jmsResourceName) {
    return traceAssert.span {
      serviceName "myService"
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
    writer.trace(2) {
      timeInQueueSpan(it, jmsResourceName, parentSpan)
      consumerSpan(it, jmsResourceName, span(0), isTimestampDisabled)
    }
  }

  static consumerSpan(TraceAssert traceAssert, String jmsResourceName, DDSpan parentSpan, boolean isTimestampDisabled = false) {
    return traceAssert.span {
      serviceName "myService"
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
        defaultTags(false)
      }
    }
  }

  static timeInQueueSpan(TraceAssert traceAssert, String jmsResourceName, DDSpan parentSpan) {
    return traceAssert.span {
      serviceName "${jmsResourceName.replaceFirst(/(Queue |Topic )/,'')}"
      operationName "jms.deliver"
      resourceName "$jmsResourceName"
      spanType DDSpanTypes.MESSAGE_BROKER
      errored false
      childOf parentSpan
      tags {
        "$Tags.COMPONENT" "jms"
        "$Tags.SPAN_KIND" Tags.SPAN_KIND_BROKER
        defaultTags(true)
      }
    }
  }
}

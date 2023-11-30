import datadog.trace.agent.test.asserts.ListWriterAssert
import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.agent.test.naming.VersionedNamingTestBase
import datadog.trace.api.Config
import datadog.trace.api.DDSpanTypes
import datadog.trace.api.Trace
import datadog.trace.api.config.TraceInstrumentationConfig
import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.core.DDSpan
import datadog.trace.test.util.Flaky
import org.apache.activemq.ActiveMQConnectionFactory
import org.apache.activemq.command.ActiveMQTextMessage
import org.apache.activemq.junit.EmbeddedActiveMQBroker
import spock.lang.Shared

import javax.jms.Connection
import javax.jms.Message
import javax.jms.MessageListener
import javax.jms.QueueConnection
import javax.jms.QueueSession
import javax.jms.Session
import javax.jms.TextMessage
import javax.jms.TopicConnection
import javax.jms.TopicSession
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

abstract class JMS1Test extends VersionedNamingTestBase {
  @Shared
  EmbeddedActiveMQBroker broker = new EmbeddedActiveMQBroker()
  @Shared
  String messageText1 = "a message"
  @Shared
  String messageText2 = "another message"
  @Shared
  String messageText3 = "yet another message"
  @Shared
  Session session
  @Shared
  QueueSession queueSession
  @Shared
  TopicSession topicSession

  @Shared
  Connection connection
  @Shared
  QueueConnection queueConnection
  @Shared
  TopicConnection topicConnection

  ActiveMQTextMessage message1 = session.createTextMessage(messageText1)
  ActiveMQTextMessage message2 = session.createTextMessage(messageText2)
  ActiveMQTextMessage message3 = session.createTextMessage(messageText3)

  abstract String operationForProducer()

  abstract String operationForConsumer()

  def setupSpec() {
    broker.start()
    final ActiveMQConnectionFactory connectionFactory = broker.createConnectionFactory()

    connection = connectionFactory.createConnection()
    connection.start()
    session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE)

    queueConnection = connectionFactory.createQueueConnection()
    queueConnection.start()
    queueSession = queueConnection.createQueueSession(false, Session.AUTO_ACKNOWLEDGE)

    topicConnection = connectionFactory.createTopicConnection()
    topicConnection.setClientID('gradle')
    topicConnection.start()
    topicSession = topicConnection.createTopicSession(false, Session.AUTO_ACKNOWLEDGE)
  }

  def cleanupSpec() {
    broker.stop()
  }

  @Flaky
  def "sending messages to #jmsResourceName generates spans"() {
    setup:
    def producer = session.createProducer(destination)
    def consumer = session.createConsumer(destination)

    when:
    producer.send(message1)
    producer.send(message2)
    producer.send(message3)

    TextMessage receivedMessage1 = consumer.receive()
    TextMessage receivedMessage2 = consumer.receive()
    TextMessage receivedMessage3 = consumer.receive()

    then:
    receivedMessage1.text == messageText1
    receivedMessage2.text == messageText2
    receivedMessage3.text == messageText3
    // only two consume traces will be finished at this point
    assertTraces(5) {
      producerTraceWithNaming(it, jmsResourceName)
      producerTraceWithNaming(it, jmsResourceName)
      producerTraceWithNaming(it, jmsResourceName)
      consumerTraceWithNaming(it, jmsResourceName, trace(0)[0])
      consumerTraceWithNaming(it, jmsResourceName, trace(1)[0])
    }

    when:
    consumer.receiveNoWait()

    then:
    // now the last consume trace will also be finished
    assertTraces(6) {
      producerTraceWithNaming(it, jmsResourceName)
      producerTraceWithNaming(it, jmsResourceName)
      producerTraceWithNaming(it, jmsResourceName)
      consumerTraceWithNaming(it, jmsResourceName, trace(0)[0])
      consumerTraceWithNaming(it, jmsResourceName, trace(1)[0])
      consumerTraceWithNaming(it, jmsResourceName, trace(2)[0])
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

  def "closing #jmsResourceName session should close and finish any pending scopes"() {
    setup:
    def localSession = connection.createSession(false, Session.AUTO_ACKNOWLEDGE)
    def producer = localSession.createProducer(destination)
    def consumer = localSession.createConsumer(destination)

    producer.send(message1)

    TextMessage receivedMessage = consumer.receive()
    localSession.close()

    expect:
    receivedMessage.text == messageText1
    assertTraces(2) {
      producerTraceWithNaming(it, jmsResourceName)
      consumerTraceWithNaming(it, jmsResourceName, trace(0)[0])
    }

    where:
    destination                      | jmsResourceName
    session.createQueue("someQueue") | "Queue someQueue"
    session.createTopic("someTopic") | "Topic someTopic"
    session.createTemporaryQueue()   | "Temporary Queue"
    session.createTemporaryTopic()   | "Temporary Topic"
  }

  def "receiving messages from #jmsResourceName in a transacted session"() {
    setup:
    def transactedSession = connection.createSession(true, Session.SESSION_TRANSACTED)
    def producer = session.createProducer(destination)
    def consumer = transactedSession.createConsumer(destination)

    when:
    producer.send(message1)
    producer.send(message2)
    producer.send(message3)

    TextMessage receivedMessage1 = consumer.receive()
    TextMessage receivedMessage2 = consumer.receive()
    transactedSession.commit()
    TextMessage receivedMessage3 = consumer.receive()

    then:
    receivedMessage1.text == messageText1
    receivedMessage2.text == messageText2
    receivedMessage3.text == messageText3
    // only two consume traces will be finished at this point
    assertTraces(5) {
      producerTraceWithNaming(it, jmsResourceName)
      producerTraceWithNaming(it, jmsResourceName)
      producerTraceWithNaming(it, jmsResourceName)
      consumerTraceWithNaming(it, jmsResourceName, trace(0)[0])
      consumerTraceWithNaming(it, jmsResourceName, trace(1)[0])
    }

    when:
    transactedSession.commit()

    then:
    // now the last consume trace will also be finished
    assertTraces(6) {
      producerTraceWithNaming(it, jmsResourceName)
      producerTraceWithNaming(it, jmsResourceName)
      producerTraceWithNaming(it, jmsResourceName)
      consumerTraceWithNaming(it, jmsResourceName, trace(0)[0])
      consumerTraceWithNaming(it, jmsResourceName, trace(1)[0])
      consumerTraceWithNaming(it, jmsResourceName, trace(2)[0])
    }

    cleanup:
    transactedSession.commit()
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

  def "receiving messages from #jmsResourceName with manual acknowledgement"() {
    setup:
    def clientSession = connection.createSession(false, Session.CLIENT_ACKNOWLEDGE)
    def producer = session.createProducer(destination)
    def consumer = clientSession.createConsumer(destination)

    when:
    producer.send(message1)
    producer.send(message2)
    producer.send(message3)

    TextMessage receivedMessage1 = consumer.receive()
    TextMessage receivedMessage2 = consumer.receive()
    receivedMessage2.acknowledge()
    TextMessage receivedMessage3 = consumer.receive()

    then:
    receivedMessage1.text == messageText1
    receivedMessage2.text == messageText2
    receivedMessage3.text == messageText3
    // only two consume traces will be finished at this point
    assertTraces(5) {
      producerTraceWithNaming(it, jmsResourceName)
      producerTraceWithNaming(it, jmsResourceName)
      producerTraceWithNaming(it, jmsResourceName)
      consumerTraceWithNaming(it, jmsResourceName, trace(0)[0])
      consumerTraceWithNaming(it, jmsResourceName, trace(1)[0])
    }

    when:
    receivedMessage3.acknowledge()

    then:
    // now the last consume trace will also be finished
    assertTraces(6) {
      producerTraceWithNaming(it, jmsResourceName)
      producerTraceWithNaming(it, jmsResourceName)
      producerTraceWithNaming(it, jmsResourceName)
      consumerTraceWithNaming(it, jmsResourceName, trace(0)[0])
      consumerTraceWithNaming(it, jmsResourceName, trace(1)[0])
      consumerTraceWithNaming(it, jmsResourceName, trace(2)[0])
    }

    cleanup:
    receivedMessage3.acknowledge()
    producer.close()
    consumer.close()
    clientSession.close()

    where:
    destination                      | jmsResourceName
    session.createQueue("someQueue") | "Queue someQueue"
    session.createTopic("someTopic") | "Topic someTopic"
    session.createTemporaryQueue()   | "Temporary Queue"
    session.createTemporaryTopic()   | "Temporary Topic"
  }

  def "recovering messages from #jmsResourceName with manual acknowledgement"() {
    setup:
    def clientSession = connection.createSession(false, Session.CLIENT_ACKNOWLEDGE)
    def producer = session.createProducer(destination)
    def consumer = clientSession.createConsumer(destination)

    when:
    producer.send(message1)
    producer.send(message2)
    producer.send(message3)

    TextMessage receivedMessage1 = consumer.receive()
    TextMessage receivedMessage2 = consumer.receive()
    clientSession.recover()

    then:
    receivedMessage1.text == messageText1
    receivedMessage2.text == messageText2
    // two consume traces will be finished at this point
    assertTraces(5) {
      producerTraceWithNaming(it, jmsResourceName)
      producerTraceWithNaming(it, jmsResourceName)
      producerTraceWithNaming(it, jmsResourceName)
      consumerTraceWithNaming(it, jmsResourceName, trace(0)[0])
      consumerTraceWithNaming(it, jmsResourceName, trace(1)[0])
    }

    when:
    receivedMessage1 = consumer.receive()
    receivedMessage2 = consumer.receive()
    TextMessage receivedMessage3 = consumer.receive()
    receivedMessage1.acknowledge()

    then:
    receivedMessage1.text == messageText1
    receivedMessage2.text == messageText2
    receivedMessage3.text == messageText3
    // the two consume traces plus three more will be finished at this point
    assertTraces(8) {
      producerTraceWithNaming(it, jmsResourceName)
      producerTraceWithNaming(it, jmsResourceName)
      producerTraceWithNaming(it, jmsResourceName)
      consumerTraceWithNaming(it, jmsResourceName, trace(0)[0])
      consumerTraceWithNaming(it, jmsResourceName, trace(1)[0])
      consumerTraceWithNaming(it, jmsResourceName, trace(0)[0]) // redelivered message
      consumerTraceWithNaming(it, jmsResourceName, trace(1)[0]) // redelivered message
      consumerTraceWithNaming(it, jmsResourceName, trace(2)[0])
    }

    cleanup:
    receivedMessage3.acknowledge()
    producer.close()
    consumer.close()
    clientSession.close()

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

    producer.send(message1)
    lock.countDown()

    expect:
    assertTraces(2) {
      producerTraceWithNaming(it, jmsResourceName)
      consumerTraceWithNaming(it, jmsResourceName, trace(0)[0])
    }
    // This check needs to go after all traces have been accounted for
    messageRef.get().text == messageText1

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

  def "sending to a null MessageListener on #jmsResourceName generates only producer spans"() {
    setup:
    def producer = session.createProducer(destination)
    def consumer = session.createConsumer(destination)
    consumer.setMessageListener(null)

    producer.send(message1)

    expect:
    assertTraces(1) {
      producerTraceWithNaming(it, jmsResourceName)
    }

    cleanup:
    producer.close()
    consumer.receiveNoWait()
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
    !message1.isReadOnlyProperties()

    when:
    message1.setReadOnlyProperties(true)
    and:
    producer.send(message1)

    TextMessage receivedMessage = consumer.receive()
    // required to finish auto-acknowledged spans
    consumer.receiveNoWait()

    then:
    receivedMessage.text == messageText1

    // This will result in a logged failure because we tried to
    // write properties in MessagePropertyTextMap when readOnlyProperties = true.
    // The consumer span will also not be linked to the parent.
    assertTraces(2) {
      producerTraceWithNaming(it, jmsResourceName)
      trace(1) {
        // Consumer trace
        span {
          parent()
          serviceName service()
          operationName operationForConsumer()
          resourceName "Consumed from $jmsResourceName"
          spanType DDSpanTypes.MESSAGE_CONSUMER
          errored false

          tags {
            "$Tags.COMPONENT" "jms"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CONSUMER
            "$InstrumentationTags.RECORD_QUEUE_TIME_MS" { it >= 0 }
            defaultTagsNoPeerService()
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
    producer.send(message1)

    boolean isTimeStampDisabled = producer.getDisableMessageTimestamp()
    consumer.receive()
    // required to finish auto-acknowledged spans
    consumer.receiveNoWait()

    expect:
    assertTraces(2) {
      producerTraceWithNaming(it, "Queue someQueue")
      consumerTraceWithNaming(it, "Queue someQueue", trace(0)[0], isTimeStampDisabled)
    }

    cleanup:
    producer.close()
    consumer.close()

  }

  def "traceable work between two receive calls has jms.consume parent"() {
    setup:
    def producer = session.createProducer(destination)
    def consumer = session.createConsumer(destination)

    producer.send(message1)

    TextMessage receivedMessage = consumer.receive()
    doStuff()
    // required to finish auto-acknowledged spans
    consumer.receiveNoWait()

    expect:
    receivedMessage.text == messageText1
    assertTraces(2) {
      producerTraceWithNaming(it, jmsResourceName)
      trace(2) {
        consumerSpan(it, jmsResourceName, trace(0)[0], false, service(), operationForConsumer())
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
    // create consumer while propagation is enabled (state will be cached)
    def consumer = session.createConsumer(destination)
    // now disable propagation for any messages produced in the given topic/queue
    injectSysConfig(TraceInstrumentationConfig.JMS_PROPAGATION_DISABLED_TOPICS, topic)
    injectSysConfig(TraceInstrumentationConfig.JMS_PROPAGATION_DISABLED_QUEUES, queue)
    def producer = session.createProducer(destination)
    producer.send(message1)
    TextMessage receivedMessage = consumer.receive()
    // required to finish auto-acknowledged spans
    consumer.receiveNoWait()
    expect:
    receivedMessage.text == messageText1
    if (expected) {
      assertTraces(2) {
        producerTraceWithNaming(it, jmsResourceName)
        consumerTraceWithNaming(it, jmsResourceName, trace(0)[0])
      }
    } else {
      assertTraces(2) {
        producerTraceWithNaming(it, jmsResourceName)
        trace(1) {
          span {
            parentSpanId(0 as BigInteger)
            serviceName service()
            operationName operationForConsumer()
            resourceName "Consumed from $jmsResourceName"
            spanType DDSpanTypes.MESSAGE_CONSUMER
            errored false
            tags {
              "$Tags.COMPONENT" "jms"
              "$Tags.SPAN_KIND" Tags.SPAN_KIND_CONSUMER
              "$InstrumentationTags.RECORD_QUEUE_TIME_MS" { it >= 0 }
              defaultTagsNoPeerService()
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
    // create consumer while propagation is disabled for given topic/queue (state will be cached)
    injectSysConfig(TraceInstrumentationConfig.JMS_PROPAGATION_DISABLED_TOPICS, topic)
    injectSysConfig(TraceInstrumentationConfig.JMS_PROPAGATION_DISABLED_QUEUES, queue)
    def consumer = session.createConsumer(destination)
    // now enable propagation for the producer and any messages produced
    removeSysConfig(TraceInstrumentationConfig.JMS_PROPAGATION_DISABLED_TOPICS)
    removeSysConfig(TraceInstrumentationConfig.JMS_PROPAGATION_DISABLED_QUEUES)
    def producer = session.createProducer(destination)
    producer.send(message1)
    TextMessage receivedMessage = consumer.receive()
    // required to finish auto-acknowledged spans
    consumer.receiveNoWait()
    expect:
    receivedMessage.text == messageText1
    if (expected) {
      assertTraces(2) {
        producerTraceWithNaming(it, jmsResourceName)
        consumerTraceWithNaming(it, jmsResourceName, trace(0)[0])
      }
    } else {
      assertTraces(2) {
        producerTraceWithNaming(it, jmsResourceName)
        trace(1) {
          span {
            parentSpanId(0 as BigInteger)
            serviceName service()
            operationName operationForConsumer()
            resourceName "Consumed from $jmsResourceName"
            spanType DDSpanTypes.MESSAGE_CONSUMER
            errored false
            tags {
              "$Tags.COMPONENT" "jms"
              "$Tags.SPAN_KIND" Tags.SPAN_KIND_CONSUMER
              "$InstrumentationTags.RECORD_QUEUE_TIME_MS" { it >= 0 }
              defaultTagsNoPeerService()
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

  def "sending a message to #jmsResourceName with given disabled topic or queue disables propagation in listener"() {
    setup:
    // create consumer while propagation is disabled for given topic/queue (state will be cached)
    injectSysConfig(TraceInstrumentationConfig.JMS_PROPAGATION_DISABLED_TOPICS, topic)
    injectSysConfig(TraceInstrumentationConfig.JMS_PROPAGATION_DISABLED_QUEUES, queue)
    def consumer = session.createConsumer(destination)
    // now enable propagation for the producer and any messages produced
    removeSysConfig(TraceInstrumentationConfig.JMS_PROPAGATION_DISABLED_TOPICS)
    removeSysConfig(TraceInstrumentationConfig.JMS_PROPAGATION_DISABLED_QUEUES)
    def producer = session.createProducer(destination)
    def lock = new CountDownLatch(1)
    def messageRef = new AtomicReference<TextMessage>()
    consumer.setMessageListener new MessageListener() {
        @Override
        void onMessage(Message message) {
          lock.await() // ensure the producer trace is reported first.
          messageRef.set(message)
        }
      }
    producer.send(message1)
    lock.countDown()

    expect:
    if (expected) {
      assertTraces(2) {
        producerTraceWithNaming(it, jmsResourceName)
        consumerTraceWithNaming(it, jmsResourceName, trace(0)[0])
      }
    } else {
      assertTraces(2) {
        producerTraceWithNaming(it, jmsResourceName)
        trace(1) {
          span {
            parentSpanId(0 as BigInteger)
            serviceName service()
            operationName operationForConsumer()
            resourceName "Consumed from $jmsResourceName"
            spanType DDSpanTypes.MESSAGE_CONSUMER
            errored false
            tags {
              "$Tags.COMPONENT" "jms"
              "$Tags.SPAN_KIND" Tags.SPAN_KIND_CONSUMER
              "$InstrumentationTags.RECORD_QUEUE_TIME_MS" { it >= 0 }
              defaultTagsNoPeerService()
            }
          }
        }
      }
    }
    // This check needs to go after all traces have been accounted for
    messageRef.get().text == messageText1

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

  def "queue session with #jmsResourceName generates spans"() {
    setup:
    def sender = queueSession.createSender(destination)
    def receiver = queueSession.createReceiver(destination)

    when:
    sender.send(message1)
    sender.send(message2)
    sender.send(message3)

    TextMessage receivedMessage1 = receiver.receive()
    TextMessage receivedMessage2 = receiver.receive()
    TextMessage receivedMessage3 = receiver.receive()

    then:
    receivedMessage1.text == messageText1
    receivedMessage2.text == messageText2
    receivedMessage3.text == messageText3
    // only two consume traces will be finished at this point
    assertTraces(5) {
      producerTraceWithNaming(it, jmsResourceName)
      producerTraceWithNaming(it, jmsResourceName)
      producerTraceWithNaming(it, jmsResourceName)
      consumerTraceWithNaming(it, jmsResourceName, trace(0)[0])
      consumerTraceWithNaming(it, jmsResourceName, trace(1)[0])
    }

    when:
    receiver.receiveNoWait()

    then:
    // now the last consume trace will also be finished
    assertTraces(6) {
      producerTraceWithNaming(it, jmsResourceName)
      producerTraceWithNaming(it, jmsResourceName)
      producerTraceWithNaming(it, jmsResourceName)
      consumerTraceWithNaming(it, jmsResourceName, trace(0)[0])
      consumerTraceWithNaming(it, jmsResourceName, trace(1)[0])
      consumerTraceWithNaming(it, jmsResourceName, trace(2)[0])
    }

    cleanup:
    sender.close()
    receiver.close()

    where:
    destination                           | jmsResourceName
    queueSession.createQueue("someQueue") | "Queue someQueue"
    queueSession.createTemporaryQueue()   | "Temporary Queue"
  }

  def "topic session with #jmsResourceName generates spans"() {
    setup:
    def publisher = topicSession.createPublisher(destination)
    def subscriber = topicSession.createSubscriber(destination)

    when:
    publisher.send(message1)
    publisher.send(message2)
    publisher.send(message3)

    TextMessage receivedMessage1 = subscriber.receive()
    TextMessage receivedMessage2 = subscriber.receive()
    TextMessage receivedMessage3 = subscriber.receive()

    then:
    receivedMessage1.text == messageText1
    receivedMessage2.text == messageText2
    receivedMessage3.text == messageText3
    // only two consume traces will be finished at this point
    assertTraces(5) {
      producerTraceWithNaming(it, jmsResourceName)
      producerTraceWithNaming(it, jmsResourceName)
      producerTraceWithNaming(it, jmsResourceName)
      consumerTraceWithNaming(it, jmsResourceName, trace(0)[0])
      consumerTraceWithNaming(it, jmsResourceName, trace(1)[0])
    }

    when:
    subscriber.receiveNoWait()

    then:
    // now the last consume trace will also be finished
    assertTraces(6) {
      producerTraceWithNaming(it, jmsResourceName)
      producerTraceWithNaming(it, jmsResourceName)
      producerTraceWithNaming(it, jmsResourceName)
      consumerTraceWithNaming(it, jmsResourceName, trace(0)[0])
      consumerTraceWithNaming(it, jmsResourceName, trace(1)[0])
      consumerTraceWithNaming(it, jmsResourceName, trace(2)[0])
    }

    cleanup:
    publisher.close()
    subscriber.close()

    where:
    destination                           | jmsResourceName
    topicSession.createTopic("someTopic") | "Topic someTopic"
    topicSession.createTemporaryTopic()   | "Temporary Topic"
  }

  def "durable topic session with #jmsResourceName generates spans"() {
    setup:
    def publisher = topicSession.createPublisher(destination)
    def subscriber = topicSession.createDurableSubscriber(destination, 'test')

    when:
    publisher.send(message1)
    publisher.send(message2)
    publisher.send(message3)

    TextMessage receivedMessage1 = subscriber.receive()
    TextMessage receivedMessage2 = subscriber.receive()
    TextMessage receivedMessage3 = subscriber.receive()

    then:
    receivedMessage1.text == messageText1
    receivedMessage2.text == messageText2
    receivedMessage3.text == messageText3
    // only two consume traces will be finished at this point
    assertTraces(5) {
      producerTraceWithNaming(it, jmsResourceName)
      producerTraceWithNaming(it, jmsResourceName)
      producerTraceWithNaming(it, jmsResourceName)
      consumerTraceWithNaming(it, jmsResourceName, trace(0)[0])
      consumerTraceWithNaming(it, jmsResourceName, trace(1)[0])
    }

    when:
    subscriber.receiveNoWait()

    then:
    // now the last consume trace will also be finished
    assertTraces(6) {
      producerTraceWithNaming(it, jmsResourceName)
      producerTraceWithNaming(it, jmsResourceName)
      producerTraceWithNaming(it, jmsResourceName)
      consumerTraceWithNaming(it, jmsResourceName, trace(0)[0])
      consumerTraceWithNaming(it, jmsResourceName, trace(1)[0])
      consumerTraceWithNaming(it, jmsResourceName, trace(2)[0])
    }

    cleanup:
    publisher.close()
    subscriber.close()

    where:
    destination                           | jmsResourceName
    topicSession.createTopic("someTopic") | "Topic someTopic"
  }

  def producerTraceWithNaming(ListWriterAssert writer, String jmsResourceName) {
    producerTrace(writer, jmsResourceName, service(), operationForProducer())
  }

  static producerTrace(ListWriterAssert writer, String jmsResourceName, String producerService = "jms", String producerOperation = "jms.produce") {
    writer.trace(1) {
      producerSpan(it, jmsResourceName, producerService, producerOperation)
    }
  }

  static producerSpan(TraceAssert traceAssert, String jmsResourceName, String producerService = "jms", String producerOperation = "jms.produce") {
    return traceAssert.span {
      serviceName producerService
      operationName producerOperation
      resourceName "Produced for $jmsResourceName"
      spanType DDSpanTypes.MESSAGE_PRODUCER
      errored false
      measured true
      parent()

      tags {
        "$Tags.COMPONENT" "jms"
        "$Tags.SPAN_KIND" Tags.SPAN_KIND_PRODUCER
        defaultTagsNoPeerService()
      }
    }
  }

  def consumerTraceWithNaming(ListWriterAssert writer, String jmsResourceName, DDSpan parentSpan, boolean isTimestampDisabled = false) {
    consumerTrace(writer, jmsResourceName, parentSpan, isTimestampDisabled, service(), operationForConsumer())
  }

  static consumerTrace(ListWriterAssert writer, String jmsResourceName, DDSpan parentSpan, boolean isTimestampDisabled = false,
    String consumerService = "jms", String consumerOperation = "jms.consume") {
    writer.trace(1) {
      consumerSpan(it, jmsResourceName, parentSpan, isTimestampDisabled, consumerService, consumerOperation)
    }
  }

  static consumerSpan(TraceAssert traceAssert, String jmsResourceName, DDSpan parentSpan, boolean isTimestampDisabled = false,
    String consumerService = "jms", String consumerOperation = "jms.consume") {
    return traceAssert.span {
      serviceName consumerService
      operationName consumerOperation
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
        defaultTagsNoPeerService(true)
      }
    }
  }

  @Trace(operationName = "do.stuff")
  def doStuff() {

  }
}

class JMS1V0Test extends JMS1Test {

  @Override
  int version() {
    0
  }

  @Override
  String service() {
    "jms"
  }

  @Override
  String operation() {
    null
  }

  @Override
  String operationForProducer() {
    "jms.produce"
  }

  @Override
  String operationForConsumer() {
    "jms.consume"
  }
}

class JMS1V1ForkedTest extends JMS1Test {

  @Override
  int version() {
    1
  }

  @Override
  String service() {
    Config.get().getServiceName()
  }

  @Override
  String operation() {
    null
  }

  @Override
  String operationForProducer() {
    "jms.send"
  }

  @Override
  String operationForConsumer() {
    "jms.process"
  }
}

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
import org.apache.activemq.ActiveMQConnectionFactory
import org.apache.activemq.command.ActiveMQTextMessage
import org.apache.activemq.junit.EmbeddedActiveMQBroker
import spock.lang.Shared

import javax.jms.Connection
import javax.jms.Destination
import javax.jms.Message
import javax.jms.MessageListener
import javax.jms.QueueConnection
import javax.jms.QueueSession
import javax.jms.Session
import javax.jms.TemporaryQueue
import javax.jms.TemporaryTopic
import javax.jms.Queue
import javax.jms.Topic
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

  /**
   * Create unique destinations of different types. If queue/topic is not temporary, the name will be unique. This
   * avoids leaking data between tests. Type is enum to get a sane toString that guarantees stable test IDs.
   */
  enum DestinationType {
    QUEUE, TOPIC, TEMPORARY_QUEUE, TEMPORARY_TOPIC

    private static int counter = 0

    Destination create(final Session session) {
      switch (this) {
        case QUEUE:
          return session.createQueue("queue-${counter++}")
        case TOPIC:
          return session.createTopic("topic-${counter++}")
        case TEMPORARY_QUEUE:
          return session.createTemporaryQueue()
        case TEMPORARY_TOPIC:
          return session.createTemporaryTopic()
        default:
          throw new IllegalArgumentException("Unknown destination type: $this")
      }
    }
  }

  def "sending messages to #destinationType generates spans"() {
    setup:
    def destination = destinationType.create(session)
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
      producerTraceWithNaming(it, destination)
      producerTraceWithNaming(it, destination)
      producerTraceWithNaming(it, destination)
      consumerTraceWithNaming(it, destination, trace(0)[0])
      consumerTraceWithNaming(it, destination, trace(1)[0])
    }

    when:
    consumer.receiveNoWait()

    then:
    // now the last consume trace will also be finished
    assertTraces(6) {
      producerTraceWithNaming(it, destination)
      producerTraceWithNaming(it, destination)
      producerTraceWithNaming(it, destination)
      consumerTraceWithNaming(it, destination, trace(0)[0])
      consumerTraceWithNaming(it, destination, trace(1)[0])
      consumerTraceWithNaming(it, destination, trace(2)[0])
    }

    cleanup:
    producer.close()
    consumer.close()

    where:
    destinationType                 | _
    DestinationType.QUEUE           | _
    DestinationType.TOPIC           | _
    DestinationType.TEMPORARY_QUEUE | _
    DestinationType.TEMPORARY_TOPIC | _
  }

  def "closing #destinationType session should close and finish any pending scopes"() {
    setup:
    def destination = destinationType.create(session)
    def localSession = connection.createSession(false, Session.AUTO_ACKNOWLEDGE)
    def producer = localSession.createProducer(destination)
    def consumer = localSession.createConsumer(destination)

    producer.send(message1)

    TextMessage receivedMessage = consumer.receive()
    localSession.close()

    expect:
    receivedMessage.text == messageText1
    assertTraces(2) {
      producerTraceWithNaming(it, destination)
      consumerTraceWithNaming(it, destination, trace(0)[0])
    }

    where:
    destinationType                 | _
    DestinationType.QUEUE           | _
    DestinationType.TOPIC           | _
    DestinationType.TEMPORARY_QUEUE | _
    DestinationType.TEMPORARY_TOPIC | _
  }

  def "receiving messages from #destinationType in a transacted session"() {
    setup:
    def destination = destinationType.create(session)
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
      producerTraceWithNaming(it, destination)
      producerTraceWithNaming(it, destination)
      producerTraceWithNaming(it, destination)
      consumerTraceWithNaming(it, destination, trace(0)[0])
      consumerTraceWithNaming(it, destination, trace(1)[0])
    }

    when:
    transactedSession.commit()

    then:
    // now the last consume trace will also be finished
    assertTraces(6) {
      producerTraceWithNaming(it, destination)
      producerTraceWithNaming(it, destination)
      producerTraceWithNaming(it, destination)
      consumerTraceWithNaming(it, destination, trace(0)[0])
      consumerTraceWithNaming(it, destination, trace(1)[0])
      consumerTraceWithNaming(it, destination, trace(2)[0])
    }

    cleanup:
    transactedSession.commit()
    producer.close()
    consumer.close()
    transactedSession.close()

    where:
    destinationType                 | _
    DestinationType.QUEUE           | _
    DestinationType.TOPIC           | _
    DestinationType.TEMPORARY_QUEUE | _
    DestinationType.TEMPORARY_TOPIC | _
  }

  def "receiving messages from #destinationType with manual acknowledgement"() {
    setup:
    def destination = destinationType.create(session)
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
      producerTraceWithNaming(it, destination)
      producerTraceWithNaming(it, destination)
      producerTraceWithNaming(it, destination)
      consumerTraceWithNaming(it, destination, trace(0)[0])
      consumerTraceWithNaming(it, destination, trace(1)[0])
    }

    when:
    receivedMessage3.acknowledge()

    then:
    // now the last consume trace will also be finished
    assertTraces(6) {
      producerTraceWithNaming(it, destination)
      producerTraceWithNaming(it, destination)
      producerTraceWithNaming(it, destination)
      consumerTraceWithNaming(it, destination, trace(0)[0])
      consumerTraceWithNaming(it, destination, trace(1)[0])
      consumerTraceWithNaming(it, destination, trace(2)[0])
    }

    cleanup:
    receivedMessage3.acknowledge()
    producer.close()
    consumer.close()
    clientSession.close()

    where:
    destinationType                 | _
    DestinationType.QUEUE           | _
    DestinationType.TOPIC           | _
    DestinationType.TEMPORARY_QUEUE | _
    DestinationType.TEMPORARY_TOPIC | _
  }

  def "recovering messages from #destinationType with manual acknowledgement"() {
    setup:
    def destination = destinationType.create(session)
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
      producerTraceWithNaming(it, destination)
      producerTraceWithNaming(it, destination)
      producerTraceWithNaming(it, destination)
      consumerTraceWithNaming(it, destination, trace(0)[0])
      consumerTraceWithNaming(it, destination, trace(1)[0])
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
      producerTraceWithNaming(it, destination)
      producerTraceWithNaming(it, destination)
      producerTraceWithNaming(it, destination)
      consumerTraceWithNaming(it, destination, trace(0)[0])
      consumerTraceWithNaming(it, destination, trace(1)[0])
      consumerTraceWithNaming(it, destination, trace(0)[0]) // redelivered message
      consumerTraceWithNaming(it, destination, trace(1)[0]) // redelivered message
      consumerTraceWithNaming(it, destination, trace(2)[0])
    }

    cleanup:
    receivedMessage3.acknowledge()
    producer.close()
    consumer.close()
    clientSession.close()

    where:
    destinationType                 | _
    DestinationType.QUEUE           | _
    DestinationType.TOPIC           | _
    DestinationType.TEMPORARY_QUEUE | _
    DestinationType.TEMPORARY_TOPIC | _
  }

  def "sending to a MessageListener on #destinationType generates a span"() {
    setup:
    def destination = destinationType.create(session)
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
      producerTraceWithNaming(it, destination)
      consumerTraceWithNaming(it, destination, trace(0)[0])
    }
    // This check needs to go after all traces have been accounted for
    messageRef.get().text == messageText1

    cleanup:
    producer.close()
    consumer.close()

    where:
    destinationType                 | _
    DestinationType.QUEUE           | _
    DestinationType.TOPIC           | _
    DestinationType.TEMPORARY_QUEUE | _
    DestinationType.TEMPORARY_TOPIC | _
  }

  def "sending to a null MessageListener on #destinationType generates only producer spans"() {
    setup:
    def destination = destinationType.create(session)
    def producer = session.createProducer(destination)
    def consumer = session.createConsumer(destination)
    consumer.setMessageListener(null)

    producer.send(message1)

    expect:
    assertTraces(1) {
      producerTraceWithNaming(it, destination)
    }

    cleanup:
    producer.close()
    consumer.receiveNoWait()
    consumer.close()

    where:
    destinationType                 | _
    DestinationType.QUEUE           | _
    DestinationType.TOPIC           | _
    DestinationType.TEMPORARY_QUEUE | _
    DestinationType.TEMPORARY_TOPIC | _
  }

  def "failing to receive message with receiveNoWait on #destinationType works"() {
    setup:
    def destination = destinationType.create(session)
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
    destinationType                 | _
    DestinationType.QUEUE           | _
    DestinationType.TOPIC           | _
  }

  def "failing to receive message with wait(timeout) on #destinationType works"() {
    setup:
    def destination = destinationType.create(session)
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
    destinationType                 | _
    DestinationType.QUEUE           | _
    DestinationType.TOPIC           | _
  }

  def "sending a read-only message to #destinationType fails"() {
    setup:
    def destination = destinationType.create(session)
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
      producerTraceWithNaming(it, destination)
      trace(1) {
        // Consumer trace
        span {
          parent()
          serviceName service()
          operationName operationForConsumer()
          resourceName "Consumed from ${toJmsResourceName(destination)}"
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
    destinationType                 | _
    DestinationType.QUEUE           | _
    DestinationType.TOPIC           | _
    DestinationType.TEMPORARY_QUEUE | _
    DestinationType.TEMPORARY_TOPIC | _
  }

  def "sending a message with disabled timestamp generates spans without specific tag"() {
    setup:
    def destination = DestinationType.QUEUE.create(session)
    def producer = session.createProducer(destination)
    def consumer = session.createConsumer(destination)

    producer.setDisableMessageTimestamp(true)
    producer.send(message1)

    boolean isTimeStampDisabled = producer.getDisableMessageTimestamp()
    consumer.receive()
    // required to finish auto-acknowledged spans
    consumer.receiveNoWait()

    expect:
    assertTraces(2) {
      producerTraceWithNaming(it, destination)
      consumerTraceWithNaming(it, destination, trace(0)[0], isTimeStampDisabled)
    }

    cleanup:
    producer.close()
    consumer.close()
  }

  def "traceable work between two #destinationType receive calls has jms.consume parent"() {
    setup:
    def destination = destinationType.create(session)
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
      producerTraceWithNaming(it, destination)
      trace(2) {
        consumerSpan(it, toJmsResourceName(destination), trace(0)[0], false, service(), operationForConsumer())
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
    destinationType                 | _
    DestinationType.QUEUE           | _
    DestinationType.TOPIC           | _
    DestinationType.TEMPORARY_QUEUE | _
    DestinationType.TEMPORARY_TOPIC | _
  }

  def "sending a message to #destinationType with given disabled topic or queue disables propagation on producer side"() {
    setup:
    def destination = destinationType.create(session)
    // create consumer while propagation is enabled (state will be cached)
    def consumer = session.createConsumer(destination)
    // now disable propagation for any messages produced in the given topic/queue
    injectSysConfig(TraceInstrumentationConfig.JMS_PROPAGATION_DISABLED_TOPICS, toName(destination))
    injectSysConfig(TraceInstrumentationConfig.JMS_PROPAGATION_DISABLED_QUEUES, toName(destination))
    def producer = session.createProducer(destination)
    producer.send(message1)
    TextMessage receivedMessage = consumer.receive()
    // required to finish auto-acknowledged spans
    consumer.receiveNoWait()
    expect:
    receivedMessage.text == messageText1
    if (expected) {
      assertTraces(2) {
        producerTraceWithNaming(it, destination)
        consumerTraceWithNaming(it, destination, trace(0)[0])
      }
    } else {
      assertTraces(2) {
        producerTraceWithNaming(it, destination)
        trace(1) {
          span {
            parentSpanId(0 as BigInteger)
            serviceName service()
            operationName operationForConsumer()
            resourceName "Consumed from ${toJmsResourceName(destination)}"
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
    destinationType                 | expected
    DestinationType.QUEUE           | false
    DestinationType.TOPIC           | false
    DestinationType.TEMPORARY_QUEUE | true
    DestinationType.TEMPORARY_TOPIC | true
  }

  def "sending a message to #destinationType with given disabled topic or queue disables propagation on consumer side"() {
    setup:
    def destination = destinationType.create(session)
    // create consumer while propagation is disabled for given topic/queue (state will be cached)
    injectSysConfig(TraceInstrumentationConfig.JMS_PROPAGATION_DISABLED_TOPICS, toName(destination))
    injectSysConfig(TraceInstrumentationConfig.JMS_PROPAGATION_DISABLED_QUEUES, toName(destination))
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
        producerTraceWithNaming(it, destination)
        consumerTraceWithNaming(it, destination, trace(0)[0])
      }
    } else {
      assertTraces(2) {
        producerTraceWithNaming(it, destination)
        trace(1) {
          span {
            parentSpanId(0 as BigInteger)
            serviceName service()
            operationName operationForConsumer()
            resourceName "Consumed from ${toJmsResourceName(destination)}"
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
    destinationType                 | expected
    DestinationType.QUEUE           | false
    DestinationType.TOPIC           | false
    DestinationType.TEMPORARY_QUEUE | true
    DestinationType.TEMPORARY_TOPIC | true
  }

  def "sending a message to #destinationType with given disabled topic or queue disables propagation in listener"() {
    setup:
    def destination = destinationType.create(session)
    // create consumer while propagation is disabled for given topic/queue (state will be cached)
    injectSysConfig(TraceInstrumentationConfig.JMS_PROPAGATION_DISABLED_TOPICS, toName(destination))
    injectSysConfig(TraceInstrumentationConfig.JMS_PROPAGATION_DISABLED_QUEUES, toName(destination))
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
        producerTraceWithNaming(it, destination)
        consumerTraceWithNaming(it, destination, trace(0)[0])
      }
    } else {
      assertTraces(2) {
        producerTraceWithNaming(it, destination)
        trace(1) {
          span {
            parentSpanId(0 as BigInteger)
            serviceName service()
            operationName operationForConsumer()
            resourceName "Consumed from ${toJmsResourceName(destination)}"
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
    destinationType                 | expected
    DestinationType.QUEUE           | false
    DestinationType.TOPIC           | false
    DestinationType.TEMPORARY_QUEUE | true
    DestinationType.TEMPORARY_TOPIC | true
  }

  def "queue session with #destinationType generates spans"() {
    setup:
    def destination = destinationType.create(queueSession)
    def sender = queueSession.createSender(destination)
    def receiver = queueSession.createReceiver(destination)

    when:
    sender.send(message1)
    sender.send(destination, message2)
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
      producerTraceWithNaming(it, destination)
      producerTraceWithNaming(it, destination)
      producerTraceWithNaming(it, destination)
      consumerTraceWithNaming(it, destination, trace(0)[0])
      consumerTraceWithNaming(it, destination, trace(1)[0])
    }

    when:
    receiver.receiveNoWait()

    then:
    // now the last consume trace will also be finished
    assertTraces(6) {
      producerTraceWithNaming(it, destination)
      producerTraceWithNaming(it, destination)
      producerTraceWithNaming(it, destination)
      consumerTraceWithNaming(it, destination, trace(0)[0])
      consumerTraceWithNaming(it, destination, trace(1)[0])
      consumerTraceWithNaming(it, destination, trace(2)[0])
    }

    cleanup:
    sender.close()
    receiver.close()

    where:
    destinationType                 | _
    DestinationType.QUEUE           | _
    DestinationType.TEMPORARY_QUEUE | _
  }

  def "topic session with #destinationType generates spans"() {
    setup:
    def destination = destinationType.create(topicSession)
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
      producerTraceWithNaming(it, destination)
      producerTraceWithNaming(it, destination)
      producerTraceWithNaming(it, destination)
      consumerTraceWithNaming(it, destination, trace(0)[0])
      consumerTraceWithNaming(it, destination, trace(1)[0])
    }

    when:
    subscriber.receiveNoWait()

    then:
    // now the last consume trace will also be finished
    assertTraces(6) {
      producerTraceWithNaming(it, destination)
      producerTraceWithNaming(it, destination)
      producerTraceWithNaming(it, destination)
      consumerTraceWithNaming(it, destination, trace(0)[0])
      consumerTraceWithNaming(it, destination, trace(1)[0])
      consumerTraceWithNaming(it, destination, trace(2)[0])
    }

    cleanup:
    publisher.close()
    subscriber.close()

    where:
    destinationType                 | _
    DestinationType.TOPIC           | _
    DestinationType.TEMPORARY_TOPIC | _
  }

  def "durable topic session generates spans"() {
    setup:
    def destination = DestinationType.TOPIC.create(topicSession)
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
      producerTraceWithNaming(it, destination)
      producerTraceWithNaming(it, destination)
      producerTraceWithNaming(it, destination)
      consumerTraceWithNaming(it, destination, trace(0)[0])
      consumerTraceWithNaming(it, destination, trace(1)[0])
    }

    when:
    subscriber.receiveNoWait()

    then:
    // now the last consume trace will also be finished
    assertTraces(6) {
      producerTraceWithNaming(it, destination)
      producerTraceWithNaming(it, destination)
      producerTraceWithNaming(it, destination)
      consumerTraceWithNaming(it, destination, trace(0)[0])
      consumerTraceWithNaming(it, destination, trace(1)[0])
      consumerTraceWithNaming(it, destination, trace(2)[0])
    }

    cleanup:
    publisher.close()
    subscriber.close()
  }

  String toJmsResourceName(Destination destination) {
    if (destination instanceof TemporaryQueue) {
      return "Temporary Queue"
    } else if (destination instanceof TemporaryTopic) {
      return "Temporary Topic"
    } else if (destination instanceof Queue) {
      return "Queue ${((Queue) destination).getQueueName()}"
    } else if (destination instanceof Topic) {
      return "Topic ${((Topic) destination).getTopicName()}"
    }
    throw new IllegalArgumentException("Unknown destination type: $destination")
  }

  String toName(Destination destination) {
    if (destination instanceof TemporaryQueue) {
      return ""
    } else if (destination instanceof TemporaryTopic) {
      return ""
    } else if (destination instanceof Queue) {
      return ((Queue) destination).getQueueName()
    } else if (destination instanceof Topic) {
      return ((Topic) destination).getTopicName()
    }
    throw new IllegalArgumentException("Unknown destination type: $destination")
  }

  def producerTraceWithNaming(ListWriterAssert writer, Destination destination) {
    producerTrace(writer, toJmsResourceName(destination), service(), operationForProducer())
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

  def consumerTraceWithNaming(ListWriterAssert writer, Destination destination, DDSpan parentSpan, boolean isTimestampDisabled = false) {
    consumerTrace(writer, toJmsResourceName(destination), parentSpan, isTimestampDisabled, service(), operationForConsumer())
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

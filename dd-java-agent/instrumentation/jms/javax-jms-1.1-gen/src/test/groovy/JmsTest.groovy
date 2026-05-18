import datadog.trace.agent.test.naming.VersionedNamingTestBase
import datadog.trace.api.DDSpanTypes
import datadog.trace.api.DDTags
import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.core.datastreams.StatsGroup
import org.apache.activemq.ActiveMQConnectionFactory
import org.apache.activemq.broker.BrokerService
import spock.lang.Shared

import javax.jms.Connection
import javax.jms.Message
import javax.jms.MessageConsumer
import javax.jms.MessageListener
import javax.jms.MessageProducer
import javax.jms.Queue
import javax.jms.Session
import javax.jms.TextMessage
import javax.jms.Topic
import javax.jms.QueueReceiver
import javax.jms.QueueSender
import javax.jms.QueueSession
import javax.jms.TemporaryQueue
import javax.jms.TemporaryTopic
import javax.jms.TopicPublisher
import javax.jms.TopicSession
import javax.jms.TopicSubscriber
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

import static datadog.trace.agent.test.utils.TraceUtils.basicSpan
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

abstract class JmsTestBase extends VersionedNamingTestBase {
  @Shared
  BrokerService broker

  @Shared
  Connection connection

  @Shared
  Session session

  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig("dd.service", "JmsTest")
    injectSysConfig("dd.jms.legacy.tracing.enabled", "true")
  }

  def setupSpec() {
    broker = new BrokerService()
    broker.setPersistent(false)
    broker.setUseJmx(false)
    broker.setBrokerName("test-broker")
    broker.start()
    broker.waitUntilStarted()

    def connectionFactory = new ActiveMQConnectionFactory("vm://localhost?broker.persistent=false")
    connection = connectionFactory.createConnection()
    connection.start()
    session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE)
  }

  def cleanupSpec() {
    session?.close()
    connection?.close()
    broker?.stop()
    broker?.waitUntilStopped()
  }

  def cleanup() {
    TEST_DATA_STREAMS_WRITER?.clear()
  }

  @Override
  int version() {
    return 0
  }

  @Override
  String operation() {
    return "jms"
  }

  @Override
  String service() {
    return "jms"
  }

  def "test jms producer send"() {
    setup:
    Queue queue = session.createQueue("test.queue")
    MessageProducer producer = session.createProducer(queue)
    MessageConsumer consumer = session.createConsumer(queue)

    when:
    runUnderTrace("parent") {
      TextMessage msg = session.createTextMessage("Hello JMS")
      producer.send(msg)
    }

    def receivedMessage = consumer.receive(5000) as TextMessage

    then:
    receivedMessage != null
    receivedMessage.text == "Hello JMS"

    assertTraces(2) {
      trace(2) {
        basicSpan(it, "parent")
        span {
          serviceName service()
          operationName operation() + ".produce"
          resourceName "Produced for Queue test.queue"
          spanType DDSpanTypes.MESSAGE_PRODUCER
          childOf span(0)
          errored false
          tags {
            "$Tags.COMPONENT" "jms"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_PRODUCER
            "messaging.system" "jms"
            "messaging.operation" "send"
            "$Tags.MESSAGE_BUS_DESTINATION" "Queue test.queue"
            "$InstrumentationTags.MESSAGING_DESTINATION_NAME" "test.queue"
            "messaging.destination.kind" "queue"
            "jms.message_type" "text"
            "messaging.message.payload_size" 9
            if ({ isDataStreamsEnabled() }) {
              "$DDTags.PATHWAY_HASH" { String }
            }
            peerServiceFrom(Tags.MESSAGE_BUS_DESTINATION)
            defaultTags()
          }
        }
      }
      trace(1) {
        span {
          serviceName service()
          operationName operation() + ".consume"
          resourceName "Consumed from Queue test.queue"
          spanType DDSpanTypes.MESSAGE_CONSUMER
          childOf trace(0)[1]
          errored false
          tags {
            "$Tags.COMPONENT" "jms"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CONSUMER
            "messaging.system" "jms"
            "messaging.operation" "receive"
            "$Tags.MESSAGE_BUS_DESTINATION" "Queue test.queue"
            "$InstrumentationTags.MESSAGING_DESTINATION_NAME" "test.queue"
            "messaging.destination.kind" "queue"
            "jms.message_type" "text"
            "messaging.message.payload_size" 9
            tag("message.id", String)
            if ({ isDataStreamsEnabled() }) {
              "$DDTags.PATHWAY_HASH" { String }
            }
            defaultTags(true)
          }
        }
      }
    }

    cleanup:
    consumer?.close()
    producer?.close()
  }

  def "test jms message listener async"() {
    setup:
    Queue queue = session.createQueue("test.listener.queue")
    MessageProducer producer = session.createProducer(queue)
    MessageConsumer consumer = session.createConsumer(queue)
    def receivedRef = new AtomicReference<TextMessage>()
    def latch = new CountDownLatch(1)

    consumer.setMessageListener(new MessageListener() {
        @Override
        void onMessage(Message message) {
          receivedRef.set(message as TextMessage)
          latch.countDown()
        }
      })

    when:
    runUnderTrace("parent") {
      TextMessage msg = session.createTextMessage("Hello Listener")
      producer.send(msg)
    }

    latch.await(10, TimeUnit.SECONDS)

    then:
    receivedRef.get() != null
    receivedRef.get().text == "Hello Listener"

    assertTraces(2) {
      trace(2) {
        basicSpan(it, "parent")
        span {
          serviceName service()
          operationName operation() + ".produce"
          resourceName "Produced for Queue test.listener.queue"
          spanType DDSpanTypes.MESSAGE_PRODUCER
          childOf span(0)
          errored false
          tags {
            "$Tags.COMPONENT" "jms"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_PRODUCER
            "messaging.system" "jms"
            "messaging.operation" "send"
            "$Tags.MESSAGE_BUS_DESTINATION" "Queue test.listener.queue"
            "$InstrumentationTags.MESSAGING_DESTINATION_NAME" "test.listener.queue"
            "messaging.destination.kind" "queue"
            "jms.message_type" "text"
            "messaging.message.payload_size" 14
            if ({ isDataStreamsEnabled() }) {
              "$DDTags.PATHWAY_HASH" { String }
            }
            peerServiceFrom(Tags.MESSAGE_BUS_DESTINATION)
            defaultTags()
          }
        }
      }
      trace(1) {
        span {
          serviceName service()
          operationName operation() + ".consume"
          resourceName "Consumed from Queue test.listener.queue"
          spanType DDSpanTypes.MESSAGE_CONSUMER
          childOf trace(0)[1]
          errored false
          tags {
            "$Tags.COMPONENT" "jms"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CONSUMER
            "messaging.system" "jms"
            "messaging.operation" "process"
            "$Tags.MESSAGE_BUS_DESTINATION" "Queue test.listener.queue"
            "$InstrumentationTags.MESSAGING_DESTINATION_NAME" "test.listener.queue"
            "messaging.destination.kind" "queue"
            "jms.message_type" "text"
            "messaging.message.payload_size" 14
            tag("message.id", String)
            if ({ isDataStreamsEnabled() }) {
              "$DDTags.PATHWAY_HASH" { String }
            }
            defaultTags(true)
          }
        }
      }
    }

    cleanup:
    consumer?.close()
    producer?.close()
  }

  def "test jms send with explicit destination"() {
    setup:
    Queue queue = session.createQueue("test.explicit.queue")
    MessageProducer producer = session.createProducer(null)
    MessageConsumer consumer = session.createConsumer(queue)

    when:
    runUnderTrace("parent") {
      TextMessage msg = session.createTextMessage("Hello Explicit")
      producer.send(queue, msg)
    }

    def receivedMessage = consumer.receive(5000) as TextMessage

    then:
    receivedMessage != null
    receivedMessage.text == "Hello Explicit"

    assertTraces(2) {
      trace(2) {
        basicSpan(it, "parent")
        span {
          serviceName service()
          operationName operation() + ".produce"
          resourceName "Produced for Queue test.explicit.queue"
          spanType DDSpanTypes.MESSAGE_PRODUCER
          childOf span(0)
          errored false
          tags {
            "$Tags.COMPONENT" "jms"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_PRODUCER
            "messaging.system" "jms"
            "messaging.operation" "send"
            "$Tags.MESSAGE_BUS_DESTINATION" "Queue test.explicit.queue"
            "$InstrumentationTags.MESSAGING_DESTINATION_NAME" "test.explicit.queue"
            "messaging.destination.kind" "queue"
            "jms.message_type" "text"
            "messaging.message.payload_size" 14
            if ({ isDataStreamsEnabled() }) {
              "$DDTags.PATHWAY_HASH" { String }
            }
            peerServiceFrom(Tags.MESSAGE_BUS_DESTINATION)
            defaultTags()
          }
        }
      }
      trace(1) {
        span {
          serviceName service()
          operationName operation() + ".consume"
          resourceName "Consumed from Queue test.explicit.queue"
          spanType DDSpanTypes.MESSAGE_CONSUMER
          childOf trace(0)[1]
          errored false
          tags {
            "$Tags.COMPONENT" "jms"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CONSUMER
            "messaging.system" "jms"
            "messaging.operation" "receive"
            "$Tags.MESSAGE_BUS_DESTINATION" "Queue test.explicit.queue"
            "$InstrumentationTags.MESSAGING_DESTINATION_NAME" "test.explicit.queue"
            "messaging.destination.kind" "queue"
            "jms.message_type" "text"
            "messaging.message.payload_size" 14
            tag("message.id", String)
            if ({ isDataStreamsEnabled() }) {
              "$DDTags.PATHWAY_HASH" { String }
            }
            defaultTags(true)
          }
        }
      }
    }

    cleanup:
    consumer?.close()
    producer?.close()
  }

  def "test jms topic pub sub"() {
    setup:
    Topic topic = session.createTopic("test.topic")
    MessageProducer publisher = session.createProducer(topic)
    MessageConsumer subscriber = session.createConsumer(topic)

    when:
    runUnderTrace("parent") {
      TextMessage msg = session.createTextMessage("Hello Topic")
      publisher.send(msg)
    }

    def receivedMessage = subscriber.receive(5000) as TextMessage

    then:
    receivedMessage != null
    receivedMessage.text == "Hello Topic"

    assertTraces(2) {
      trace(2) {
        basicSpan(it, "parent")
        span {
          serviceName service()
          operationName operation() + ".produce"
          resourceName "Produced for Topic test.topic"
          spanType DDSpanTypes.MESSAGE_PRODUCER
          childOf span(0)
          errored false
          tags {
            "$Tags.COMPONENT" "jms"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_PRODUCER
            "messaging.system" "jms"
            "messaging.operation" "send"
            "$Tags.MESSAGE_BUS_DESTINATION" "Topic test.topic"
            "$InstrumentationTags.MESSAGING_DESTINATION_NAME" "test.topic"
            "messaging.destination.kind" "topic"
            "jms.message_type" "text"
            "messaging.message.payload_size" 11
            if ({ isDataStreamsEnabled() }) {
              "$DDTags.PATHWAY_HASH" { String }
            }
            peerServiceFrom(Tags.MESSAGE_BUS_DESTINATION)
            defaultTags()
          }
        }
      }
      trace(1) {
        span {
          serviceName service()
          operationName operation() + ".consume"
          resourceName "Consumed from Topic test.topic"
          spanType DDSpanTypes.MESSAGE_CONSUMER
          childOf trace(0)[1]
          errored false
          tags {
            "$Tags.COMPONENT" "jms"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CONSUMER
            "messaging.system" "jms"
            "messaging.operation" "receive"
            "$Tags.MESSAGE_BUS_DESTINATION" "Topic test.topic"
            "$InstrumentationTags.MESSAGING_DESTINATION_NAME" "test.topic"
            "messaging.destination.kind" "topic"
            "jms.message_type" "text"
            "messaging.message.payload_size" 11
            tag("message.id", String)
            if ({ isDataStreamsEnabled() }) {
              "$DDTags.PATHWAY_HASH" { String }
            }
            defaultTags(true)
          }
        }
      }
    }

    cleanup:
    subscriber?.close()
    publisher?.close()
  }

  def "test jms topic publisher publish"() {
    setup:
    TopicSession topicSession = session as TopicSession
    Topic topic = topicSession.createTopic("test.publisher.topic")
    TopicPublisher publisher = topicSession.createPublisher(topic)
    MessageConsumer subscriber = session.createConsumer(topic)

    when:
    runUnderTrace("parent") {
      TextMessage msg = session.createTextMessage("Hello TopicPublisher")
      publisher.publish(msg)
    }

    def receivedMessage = subscriber.receive(5000) as TextMessage

    then:
    receivedMessage != null
    receivedMessage.text == "Hello TopicPublisher"

    assertTraces(2) {
      trace(2) {
        basicSpan(it, "parent")
        span {
          serviceName service()
          operationName operation() + ".produce"
          resourceName "Produced for Topic test.publisher.topic"
          spanType DDSpanTypes.MESSAGE_PRODUCER
          childOf span(0)
          errored false
          tags {
            "$Tags.COMPONENT" "jms"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_PRODUCER
            "messaging.system" "jms"
            "messaging.operation" "send"
            "$Tags.MESSAGE_BUS_DESTINATION" "Topic test.publisher.topic"
            "$InstrumentationTags.MESSAGING_DESTINATION_NAME" "test.publisher.topic"
            "messaging.destination.kind" "topic"
            "jms.message_type" "text"
            "messaging.message.payload_size" 20
            if ({ isDataStreamsEnabled() }) {
              "$DDTags.PATHWAY_HASH" { String }
            }
            peerServiceFrom(Tags.MESSAGE_BUS_DESTINATION)
            defaultTags()
          }
        }
      }
      trace(1) {
        span {
          serviceName service()
          operationName operation() + ".consume"
          resourceName "Consumed from Topic test.publisher.topic"
          spanType DDSpanTypes.MESSAGE_CONSUMER
          childOf trace(0)[1]
          errored false
          tags {
            "$Tags.COMPONENT" "jms"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CONSUMER
            "messaging.system" "jms"
            "messaging.operation" "receive"
            "$Tags.MESSAGE_BUS_DESTINATION" "Topic test.publisher.topic"
            "$InstrumentationTags.MESSAGING_DESTINATION_NAME" "test.publisher.topic"
            "messaging.destination.kind" "topic"
            "jms.message_type" "text"
            "messaging.message.payload_size" 20
            tag("message.id", String)
            if ({ isDataStreamsEnabled() }) {
              "$DDTags.PATHWAY_HASH" { String }
            }
            defaultTags(true)
          }
        }
      }
    }

    cleanup:
    subscriber?.close()
    publisher?.close()
  }

  def "test jms queue sender send"() {
    setup:
    QueueSession queueSession = session as QueueSession
    Queue queue = queueSession.createQueue("test.sender.queue")
    QueueSender sender = queueSession.createSender(queue)
    MessageConsumer consumer = session.createConsumer(queue)

    when:
    runUnderTrace("parent") {
      TextMessage msg = session.createTextMessage("Hello QueueSender")
      sender.send(msg)
    }

    def receivedMessage = consumer.receive(5000) as TextMessage

    then:
    receivedMessage != null
    receivedMessage.text == "Hello QueueSender"

    assertTraces(2) {
      trace(2) {
        basicSpan(it, "parent")
        span {
          serviceName service()
          operationName operation() + ".produce"
          resourceName "Produced for Queue test.sender.queue"
          spanType DDSpanTypes.MESSAGE_PRODUCER
          childOf span(0)
          errored false
          tags {
            "$Tags.COMPONENT" "jms"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_PRODUCER
            "messaging.system" "jms"
            "messaging.operation" "send"
            "$Tags.MESSAGE_BUS_DESTINATION" "Queue test.sender.queue"
            "$InstrumentationTags.MESSAGING_DESTINATION_NAME" "test.sender.queue"
            "messaging.destination.kind" "queue"
            "jms.message_type" "text"
            "messaging.message.payload_size" 17
            if ({ isDataStreamsEnabled() }) {
              "$DDTags.PATHWAY_HASH" { String }
            }
            peerServiceFrom(Tags.MESSAGE_BUS_DESTINATION)
            defaultTags()
          }
        }
      }
      trace(1) {
        span {
          serviceName service()
          operationName operation() + ".consume"
          resourceName "Consumed from Queue test.sender.queue"
          spanType DDSpanTypes.MESSAGE_CONSUMER
          childOf trace(0)[1]
          errored false
          tags {
            "$Tags.COMPONENT" "jms"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CONSUMER
            "messaging.system" "jms"
            "messaging.operation" "receive"
            "$Tags.MESSAGE_BUS_DESTINATION" "Queue test.sender.queue"
            "$InstrumentationTags.MESSAGING_DESTINATION_NAME" "test.sender.queue"
            "messaging.destination.kind" "queue"
            "jms.message_type" "text"
            "messaging.message.payload_size" 17
            tag("message.id", String)
            if ({ isDataStreamsEnabled() }) {
              "$DDTags.PATHWAY_HASH" { String }
            }
            defaultTags(true)
          }
        }
      }
    }

    cleanup:
    consumer?.close()
    sender?.close()
  }

  def "test jms queue receiver receive"() {
    setup:
    QueueSession queueSession = session as QueueSession
    Queue queue = queueSession.createQueue("test.receiver.queue")
    MessageProducer producer = session.createProducer(queue)
    QueueReceiver receiver = queueSession.createReceiver(queue)

    when:
    runUnderTrace("parent") {
      TextMessage msg = session.createTextMessage("Hello QueueReceiver")
      producer.send(msg)
    }

    def receivedMessage = receiver.receive(5000) as TextMessage

    then:
    receivedMessage != null
    receivedMessage.text == "Hello QueueReceiver"

    assertTraces(2) {
      trace(2) {
        basicSpan(it, "parent")
        span {
          serviceName service()
          operationName operation() + ".produce"
          resourceName "Produced for Queue test.receiver.queue"
          spanType DDSpanTypes.MESSAGE_PRODUCER
          childOf span(0)
          errored false
          tags {
            "$Tags.COMPONENT" "jms"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_PRODUCER
            "messaging.system" "jms"
            "messaging.operation" "send"
            "$Tags.MESSAGE_BUS_DESTINATION" "Queue test.receiver.queue"
            "$InstrumentationTags.MESSAGING_DESTINATION_NAME" "test.receiver.queue"
            "messaging.destination.kind" "queue"
            "jms.message_type" "text"
            "messaging.message.payload_size" 19
            if ({ isDataStreamsEnabled() }) {
              "$DDTags.PATHWAY_HASH" { String }
            }
            peerServiceFrom(Tags.MESSAGE_BUS_DESTINATION)
            defaultTags()
          }
        }
      }
      trace(1) {
        span {
          serviceName service()
          operationName operation() + ".consume"
          resourceName "Consumed from Queue test.receiver.queue"
          spanType DDSpanTypes.MESSAGE_CONSUMER
          childOf trace(0)[1]
          errored false
          tags {
            "$Tags.COMPONENT" "jms"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CONSUMER
            "messaging.system" "jms"
            "messaging.operation" "receive"
            "$Tags.MESSAGE_BUS_DESTINATION" "Queue test.receiver.queue"
            "$InstrumentationTags.MESSAGING_DESTINATION_NAME" "test.receiver.queue"
            "messaging.destination.kind" "queue"
            "jms.message_type" "text"
            "messaging.message.payload_size" 19
            tag("message.id", String)
            if ({ isDataStreamsEnabled() }) {
              "$DDTags.PATHWAY_HASH" { String }
            }
            defaultTags(true)
          }
        }
      }
    }

    cleanup:
    receiver?.close()
    producer?.close()
  }

  def "test jms topic subscriber receive"() {
    setup:
    TopicSession topicSession = session as TopicSession
    Topic topic = topicSession.createTopic("test.subscriber.topic")
    TopicSubscriber subscriber = topicSession.createSubscriber(topic)
    MessageProducer producer = session.createProducer(topic)

    when:
    runUnderTrace("parent") {
      TextMessage msg = session.createTextMessage("Hello TopicSubscriber")
      producer.send(msg)
    }

    def receivedMessage = subscriber.receive(5000) as TextMessage

    then:
    receivedMessage != null
    receivedMessage.text == "Hello TopicSubscriber"

    assertTraces(2) {
      trace(2) {
        basicSpan(it, "parent")
        span {
          serviceName service()
          operationName operation() + ".produce"
          resourceName "Produced for Topic test.subscriber.topic"
          spanType DDSpanTypes.MESSAGE_PRODUCER
          childOf span(0)
          errored false
          tags {
            "$Tags.COMPONENT" "jms"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_PRODUCER
            "messaging.system" "jms"
            "messaging.operation" "send"
            "$Tags.MESSAGE_BUS_DESTINATION" "Topic test.subscriber.topic"
            "$InstrumentationTags.MESSAGING_DESTINATION_NAME" "test.subscriber.topic"
            "messaging.destination.kind" "topic"
            "jms.message_type" "text"
            "messaging.message.payload_size" 21
            if ({ isDataStreamsEnabled() }) {
              "$DDTags.PATHWAY_HASH" { String }
            }
            peerServiceFrom(Tags.MESSAGE_BUS_DESTINATION)
            defaultTags()
          }
        }
      }
      trace(1) {
        span {
          serviceName service()
          operationName operation() + ".consume"
          resourceName "Consumed from Topic test.subscriber.topic"
          spanType DDSpanTypes.MESSAGE_CONSUMER
          childOf trace(0)[1]
          errored false
          tags {
            "$Tags.COMPONENT" "jms"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CONSUMER
            "messaging.system" "jms"
            "messaging.operation" "receive"
            "$Tags.MESSAGE_BUS_DESTINATION" "Topic test.subscriber.topic"
            "$InstrumentationTags.MESSAGING_DESTINATION_NAME" "test.subscriber.topic"
            "messaging.destination.kind" "topic"
            "jms.message_type" "text"
            "messaging.message.payload_size" 21
            tag("message.id", String)
            if ({ isDataStreamsEnabled() }) {
              "$DDTags.PATHWAY_HASH" { String }
            }
            defaultTags(true)
          }
        }
      }
    }

    cleanup:
    subscriber?.close()
    producer?.close()
  }

  def "test jms message listener error handling"() {
    setup:
    // Use a separate connection with redelivery disabled so the trace count is deterministic
    def errorConnectionFactory = new ActiveMQConnectionFactory(
      "vm://localhost?broker.persistent=false&jms.redeliveryPolicy.maximumRedeliveries=0")
    def errorConnection = errorConnectionFactory.createConnection()
    errorConnection.start()
    def errorSession = errorConnection.createSession(false, Session.AUTO_ACKNOWLEDGE)
    Queue queue = errorSession.createQueue("test.error.queue")
    MessageProducer producer = errorSession.createProducer(queue)
    MessageConsumer consumer = errorSession.createConsumer(queue)
    def latch = new CountDownLatch(1)

    consumer.setMessageListener(new MessageListener() {
        @Override
        void onMessage(Message message) {
          latch.countDown()
          throw new RuntimeException("listener error")
        }
      })

    when:
    runUnderTrace("parent") {
      TextMessage msg = errorSession.createTextMessage("Hello Error")
      producer.send(msg)
    }

    latch.await(10, TimeUnit.SECONDS)

    then:
    assertTraces(2) {
      trace(2) {
        basicSpan(it, "parent")
        span {
          serviceName service()
          operationName operation() + ".produce"
          resourceName "Produced for Queue test.error.queue"
          spanType DDSpanTypes.MESSAGE_PRODUCER
          childOf span(0)
          errored false
          tags {
            "$Tags.COMPONENT" "jms"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_PRODUCER
            "messaging.system" "jms"
            "messaging.operation" "send"
            "$Tags.MESSAGE_BUS_DESTINATION" "Queue test.error.queue"
            "$InstrumentationTags.MESSAGING_DESTINATION_NAME" "test.error.queue"
            "messaging.destination.kind" "queue"
            "jms.message_type" "text"
            "messaging.message.payload_size" 11
            if ({ isDataStreamsEnabled() }) {
              "$DDTags.PATHWAY_HASH" { String }
            }
            peerServiceFrom(Tags.MESSAGE_BUS_DESTINATION)
            defaultTags()
          }
        }
      }
      trace(1) {
        span {
          serviceName service()
          operationName operation() + ".consume"
          resourceName "Consumed from Queue test.error.queue"
          spanType DDSpanTypes.MESSAGE_CONSUMER
          childOf trace(0)[1]
          errored true
          tags {
            "$Tags.COMPONENT" "jms"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CONSUMER
            "messaging.system" "jms"
            "messaging.operation" "process"
            "$Tags.MESSAGE_BUS_DESTINATION" "Queue test.error.queue"
            "$InstrumentationTags.MESSAGING_DESTINATION_NAME" "test.error.queue"
            "messaging.destination.kind" "queue"
            "jms.message_type" "text"
            "messaging.message.payload_size" 11
            tag("message.id", String)
            errorTags(RuntimeException, "listener error")
            if ({ isDataStreamsEnabled() }) {
              "$DDTags.PATHWAY_HASH" { String }
            }
            defaultTags(true)
          }
        }
      }
    }

    cleanup:
    consumer?.close()
    producer?.close()
    errorSession?.close()
    errorConnection?.close()
  }

  def "test DSM checkpoint on queue produce and consume"() {
    setup:
    Queue queue = session.createQueue("dsm.test.queue")
    MessageProducer producer = session.createProducer(queue)
    MessageConsumer consumer = session.createConsumer(queue)

    when:
    runUnderTrace("parent") {
      TextMessage msg = session.createTextMessage("Hello DSM")
      producer.send(msg)
    }

    def receivedMessage = consumer.receive(5000) as TextMessage

    if (isDataStreamsEnabled()) {
      TEST_DATA_STREAMS_WRITER.waitForGroups(2)
    }

    then:
    receivedMessage != null
    receivedMessage.text == "Hello DSM"

    and:
    if (isDataStreamsEnabled()) {
      StatsGroup first = TEST_DATA_STREAMS_WRITER.groups.find { it.parentHash == 0 }
      verifyAll(first) {
        tags.hasAllTags("direction:out", "topic:dsm.test.queue", "type:jms")
      }

      StatsGroup second = TEST_DATA_STREAMS_WRITER.groups.find { it.parentHash == first.hash }
      verifyAll(second) {
        tags.hasAllTags("direction:in", "topic:dsm.test.queue", "type:jms")
      }
    }

    cleanup:
    consumer?.close()
    producer?.close()
  }

  def "test DSM checkpoint on topic produce and consume"() {
    setup:
    Topic topic = session.createTopic("dsm.test.topic")
    MessageProducer publisher = session.createProducer(topic)
    MessageConsumer subscriber = session.createConsumer(topic)

    when:
    runUnderTrace("parent") {
      TextMessage msg = session.createTextMessage("Hello DSM Topic")
      publisher.send(msg)
    }

    def receivedMessage = subscriber.receive(5000) as TextMessage

    if (isDataStreamsEnabled()) {
      TEST_DATA_STREAMS_WRITER.waitForGroups(2)
    }

    then:
    receivedMessage != null
    receivedMessage.text == "Hello DSM Topic"

    and:
    if (isDataStreamsEnabled()) {
      StatsGroup first = TEST_DATA_STREAMS_WRITER.groups.find { it.parentHash == 0 }
      verifyAll(first) {
        tags.hasAllTags("direction:out", "topic:dsm.test.topic", "type:jms")
      }

      StatsGroup second = TEST_DATA_STREAMS_WRITER.groups.find { it.parentHash == first.hash }
      verifyAll(second) {
        tags.hasAllTags("direction:in", "topic:dsm.test.topic", "type:jms")
      }
    }

    cleanup:
    subscriber?.close()
    publisher?.close()
  }

  def "test DSM checkpoint on message listener consume"() {
    setup:
    Queue queue = session.createQueue("dsm.listener.queue")
    MessageProducer producer = session.createProducer(queue)
    MessageConsumer consumer = session.createConsumer(queue)
    def receivedRef = new AtomicReference<TextMessage>()
    def latch = new CountDownLatch(1)

    consumer.setMessageListener(new MessageListener() {
        @Override
        void onMessage(Message message) {
          receivedRef.set(message as TextMessage)
          latch.countDown()
        }
      })

    when:
    runUnderTrace("parent") {
      TextMessage msg = session.createTextMessage("Hello DSM Listener")
      producer.send(msg)
    }

    latch.await(10, TimeUnit.SECONDS)

    if (isDataStreamsEnabled()) {
      TEST_DATA_STREAMS_WRITER.waitForGroups(2)
    }

    then:
    receivedRef.get() != null
    receivedRef.get().text == "Hello DSM Listener"

    and:
    if (isDataStreamsEnabled()) {
      StatsGroup first = TEST_DATA_STREAMS_WRITER.groups.find { it.parentHash == 0 }
      verifyAll(first) {
        tags.hasAllTags("direction:out", "topic:dsm.listener.queue", "type:jms")
      }

      StatsGroup second = TEST_DATA_STREAMS_WRITER.groups.find { it.parentHash == first.hash }
      verifyAll(second) {
        tags.hasAllTags("direction:in", "topic:dsm.listener.queue", "type:jms")
      }
    }

    cleanup:
    consumer?.close()
    producer?.close()
  }

  def "test jms temporary queue destination naming"() {
    setup:
    TemporaryQueue tempQueue = session.createTemporaryQueue()
    MessageProducer producer = session.createProducer(tempQueue)
    MessageConsumer consumer = session.createConsumer(tempQueue)

    when:
    runUnderTrace("parent") {
      TextMessage msg = session.createTextMessage("Hello TempQueue")
      producer.send(msg)
    }

    def receivedMessage = consumer.receive(5000) as TextMessage

    then:
    receivedMessage != null
    receivedMessage.text == "Hello TempQueue"

    assertTraces(2) {
      trace(2) {
        basicSpan(it, "parent")
        span {
          serviceName service()
          operationName operation() + ".produce"
          resourceName({ it.toString().startsWith("Produced for Temporary Queue ") })
          spanType DDSpanTypes.MESSAGE_PRODUCER
          childOf span(0)
          errored false
          tags {
            "$Tags.COMPONENT" "jms"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_PRODUCER
            "messaging.system" "jms"
            "messaging.operation" "send"
            "$Tags.MESSAGE_BUS_DESTINATION" { it.toString().startsWith("Temporary Queue ") }
            "$InstrumentationTags.MESSAGING_DESTINATION_NAME" { String }
            "messaging.destination.kind" "queue"
            "jms.message_type" "text"
            "messaging.message.payload_size" 15
            if ({ isDataStreamsEnabled() }) {
              "$DDTags.PATHWAY_HASH" { String }
            }
            peerServiceFrom(Tags.MESSAGE_BUS_DESTINATION)
            defaultTags()
          }
        }
      }
      trace(1) {
        span {
          serviceName service()
          operationName operation() + ".consume"
          resourceName({ it.toString().startsWith("Consumed from Temporary Queue ") })
          spanType DDSpanTypes.MESSAGE_CONSUMER
          childOf trace(0)[1]
          errored false
          tags {
            "$Tags.COMPONENT" "jms"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CONSUMER
            "messaging.system" "jms"
            "messaging.operation" "receive"
            "$Tags.MESSAGE_BUS_DESTINATION" { it.toString().startsWith("Temporary Queue ") }
            "$InstrumentationTags.MESSAGING_DESTINATION_NAME" { String }
            "messaging.destination.kind" "queue"
            "jms.message_type" "text"
            "messaging.message.payload_size" 15
            tag("message.id", String)
            if ({ isDataStreamsEnabled() }) {
              "$DDTags.PATHWAY_HASH" { String }
            }
            defaultTags(true)
          }
        }
      }
    }

    cleanup:
    consumer?.close()
    producer?.close()
    tempQueue?.delete()
  }

  def "test jms temporary topic destination naming"() {
    setup:
    TemporaryTopic tempTopic = session.createTemporaryTopic()
    MessageProducer producer = session.createProducer(tempTopic)
    MessageConsumer subscriber = session.createConsumer(tempTopic)

    when:
    runUnderTrace("parent") {
      TextMessage msg = session.createTextMessage("Hello TempTopic")
      producer.send(msg)
    }

    def receivedMessage = subscriber.receive(5000) as TextMessage

    then:
    receivedMessage != null
    receivedMessage.text == "Hello TempTopic"

    assertTraces(2) {
      trace(2) {
        basicSpan(it, "parent")
        span {
          serviceName service()
          operationName operation() + ".produce"
          resourceName({ it.toString().startsWith("Produced for Temporary Topic ") })
          spanType DDSpanTypes.MESSAGE_PRODUCER
          childOf span(0)
          errored false
          tags {
            "$Tags.COMPONENT" "jms"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_PRODUCER
            "messaging.system" "jms"
            "messaging.operation" "send"
            "$Tags.MESSAGE_BUS_DESTINATION" { it.toString().startsWith("Temporary Topic ") }
            "$InstrumentationTags.MESSAGING_DESTINATION_NAME" { String }
            "messaging.destination.kind" "topic"
            "jms.message_type" "text"
            "messaging.message.payload_size" 15
            if ({ isDataStreamsEnabled() }) {
              "$DDTags.PATHWAY_HASH" { String }
            }
            peerServiceFrom(Tags.MESSAGE_BUS_DESTINATION)
            defaultTags()
          }
        }
      }
      trace(1) {
        span {
          serviceName service()
          operationName operation() + ".consume"
          resourceName({ it.toString().startsWith("Consumed from Temporary Topic ") })
          spanType DDSpanTypes.MESSAGE_CONSUMER
          childOf trace(0)[1]
          errored false
          tags {
            "$Tags.COMPONENT" "jms"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CONSUMER
            "messaging.system" "jms"
            "messaging.operation" "receive"
            "$Tags.MESSAGE_BUS_DESTINATION" { it.toString().startsWith("Temporary Topic ") }
            "$InstrumentationTags.MESSAGING_DESTINATION_NAME" { String }
            "messaging.destination.kind" "topic"
            "jms.message_type" "text"
            "messaging.message.payload_size" 15
            tag("message.id", String)
            if ({ isDataStreamsEnabled() }) {
              "$DDTags.PATHWAY_HASH" { String }
            }
            defaultTags(true)
          }
        }
      }
    }

    cleanup:
    subscriber?.close()
    producer?.close()
    tempTopic?.delete()
  }

  def "test trace context propagation via JMS message properties"() {
    setup:
    Queue queue = session.createQueue("test.propagation.queue")
    MessageProducer producer = session.createProducer(queue)
    MessageConsumer consumer = session.createConsumer(queue)

    when:
    runUnderTrace("parent") {
      TextMessage msg = session.createTextMessage("Hello Propagation")
      producer.send(msg)
    }

    def receivedMessage = consumer.receive(5000) as TextMessage

    then:
    receivedMessage != null
    receivedMessage.text == "Hello Propagation"

    receivedMessage.getStringProperty("x__]]datadog__]s]trace__]s]id") != null ||
      receivedMessage.getStringProperty("x-datadog-trace-id") != null ||
      receivedMessage.getStringProperty("traceparent") != null

    assertTraces(2) {
      trace(2) {
        basicSpan(it, "parent")
        span {
          serviceName service()
          operationName operation() + ".produce"
          resourceName "Produced for Queue test.propagation.queue"
          spanType DDSpanTypes.MESSAGE_PRODUCER
          childOf span(0)
          errored false
          tags {
            "$Tags.COMPONENT" "jms"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_PRODUCER
            "messaging.system" "jms"
            "messaging.operation" "send"
            "$Tags.MESSAGE_BUS_DESTINATION" "Queue test.propagation.queue"
            "$InstrumentationTags.MESSAGING_DESTINATION_NAME" "test.propagation.queue"
            "messaging.destination.kind" "queue"
            "jms.message_type" "text"
            "messaging.message.payload_size" 17
            if ({ isDataStreamsEnabled() }) {
              "$DDTags.PATHWAY_HASH" { String }
            }
            peerServiceFrom(Tags.MESSAGE_BUS_DESTINATION)
            defaultTags()
          }
        }
      }
      trace(1) {
        span {
          serviceName service()
          operationName operation() + ".consume"
          resourceName "Consumed from Queue test.propagation.queue"
          spanType DDSpanTypes.MESSAGE_CONSUMER
          childOf trace(0)[1]
          errored false
          tags {
            "$Tags.COMPONENT" "jms"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CONSUMER
            "messaging.system" "jms"
            "messaging.operation" "receive"
            "$Tags.MESSAGE_BUS_DESTINATION" "Queue test.propagation.queue"
            "$InstrumentationTags.MESSAGING_DESTINATION_NAME" "test.propagation.queue"
            "messaging.destination.kind" "queue"
            "jms.message_type" "text"
            "messaging.message.payload_size" 17
            tag("message.id", String)
            if ({ isDataStreamsEnabled() }) {
              "$DDTags.PATHWAY_HASH" { String }
            }
            defaultTags(true)
          }
        }
      }
    }

    cleanup:
    consumer?.close()
    producer?.close()
  }

  def "test jms message type tag for #messageType"() {
    setup:
    Queue queue = session.createQueue("test.msgtype." + messageType + ".queue")
    MessageProducer producer = session.createProducer(queue)
    MessageConsumer consumer = session.createConsumer(queue)

    when:
    runUnderTrace("parent") {
      producer.send(message)
    }

    def receivedMessage = consumer.receive(5000)

    then:
    receivedMessage != null

    assertTraces(2) {
      trace(2) {
        basicSpan(it, "parent")
        span {
          serviceName service()
          operationName operation() + ".produce"
          resourceName "Produced for Queue test.msgtype.${messageType}.queue"
          spanType DDSpanTypes.MESSAGE_PRODUCER
          childOf span(0)
          errored false
          tags {
            "$Tags.COMPONENT" "jms"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_PRODUCER
            "messaging.system" "jms"
            "messaging.operation" "send"
            "$Tags.MESSAGE_BUS_DESTINATION" "Queue test.msgtype.${messageType}.queue"
            "$InstrumentationTags.MESSAGING_DESTINATION_NAME" "test.msgtype.${messageType}.queue"
            "messaging.destination.kind" "queue"
            "jms.message_type" expectedType
            if (messageType == "text") {
              "messaging.message.payload_size" { it instanceof Number }
            }
            if ({ isDataStreamsEnabled() }) {
              "$DDTags.PATHWAY_HASH" { String }
            }
            peerServiceFrom(Tags.MESSAGE_BUS_DESTINATION)
            defaultTags()
          }
        }
      }
      trace(1) {
        span {
          serviceName service()
          operationName operation() + ".consume"
          resourceName "Consumed from Queue test.msgtype.${messageType}.queue"
          spanType DDSpanTypes.MESSAGE_CONSUMER
          childOf trace(0)[1]
          errored false
          tags {
            "$Tags.COMPONENT" "jms"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CONSUMER
            "messaging.system" "jms"
            "messaging.operation" "receive"
            "$Tags.MESSAGE_BUS_DESTINATION" "Queue test.msgtype.${messageType}.queue"
            "$InstrumentationTags.MESSAGING_DESTINATION_NAME" "test.msgtype.${messageType}.queue"
            "messaging.destination.kind" "queue"
            "jms.message_type" expectedType
            if (messageType == "text") {
              "messaging.message.payload_size" { it instanceof Number }
            }
            tag("message.id", String)
            if ({ isDataStreamsEnabled() }) {
              "$DDTags.PATHWAY_HASH" { String }
            }
            defaultTags(true)
          }
        }
      }
    }

    cleanup:
    consumer?.close()
    producer?.close()

    where:
    messageType | expectedType | message
    "bytes"     | "bytes"      | { -> def m = session.createBytesMessage(); m.writeBytes("hello".bytes); m }()
    "map"       | "map"        | { -> def m = session.createMapMessage(); m.setString("key", "value"); m }()
    "stream"    | "stream"     | { -> def m = session.createStreamMessage(); m.writeString("hello"); m }()
    "object"    | "object"     | { -> session.createObjectMessage("hello") }()
  }
}

class JmsTest extends JmsTestBase {}

class JmsDataStreamsTest extends JmsTestBase {
  @Override
  boolean isDataStreamsEnabled() {
    return true
  }
}

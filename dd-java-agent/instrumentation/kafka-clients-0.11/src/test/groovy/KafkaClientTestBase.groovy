import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.core.DDSpan
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.Producer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.StringSerializer
import org.junit.Rule
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.listener.BatchMessageListener
import org.springframework.kafka.listener.KafkaMessageListenerContainer
import org.springframework.kafka.listener.MessageListener
import org.springframework.kafka.test.rule.KafkaEmbedded
import org.springframework.kafka.test.utils.ContainerTestUtils
import org.springframework.kafka.test.utils.KafkaTestUtils
import spock.lang.Requires
import spock.lang.Unroll

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

import static datadog.trace.agent.test.utils.TraceUtils.basicSpan
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeScope
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan

abstract class KafkaClientTestBase extends AgentTestRunner {
  static final SHARED_TOPIC = "shared.topic"

  @Rule
  KafkaEmbedded embeddedKafka = new KafkaEmbedded(1, true, SHARED_TOPIC)

  @Override
  void configurePreAgent() {
    super.configurePreAgent()

    injectSysConfig("dd.kafka.e2e.duration.enabled", "true")
  }

  abstract String expectedServiceName()

  abstract boolean hasQueueSpan()

  abstract boolean splitByDestination()

  def "test kafka produce and consume"() {
    setup:
    def senderProps = KafkaTestUtils.senderProps(embeddedKafka.getBrokersAsString())
    Producer<String, String> producer = new KafkaProducer<>(senderProps, new StringSerializer(), new StringSerializer())

    // set up the Kafka consumer properties
    def consumerProperties = KafkaTestUtils.consumerProps("sender", "false", embeddedKafka)

    // create a Kafka consumer factory
    def consumerFactory = new DefaultKafkaConsumerFactory<String, String>(consumerProperties)

    // set the topic that needs to be consumed
    def containerProperties = containerProperties()

    // create a Kafka MessageListenerContainer
    def container = new KafkaMessageListenerContainer<>(consumerFactory, containerProperties)

    // create a thread safe queue to store the received message
    def records = new LinkedBlockingQueue<ConsumerRecord<String, String>>()

    // setup a Kafka message listener
    container.setupMessageListener(new MessageListener<String, String>() {
        @Override
        void onMessage(ConsumerRecord<String, String> record) {
          TEST_WRITER.waitForTraces(1) // ensure consistent ordering of traces
          records.add(record)
        }
      })

    // start the container and underlying message listener
    container.start()

    // wait until the container has the required number of assigned partitions
    ContainerTestUtils.waitForAssignment(container, embeddedKafka.getPartitionsPerTopic())

    when:
    String greeting = "Hello Spring Kafka Sender!"
    runUnderTrace("parent") {
      producer.send(new ProducerRecord(SHARED_TOPIC, greeting)) { meta, ex ->
        assert activeScope().isAsyncPropagating()
        if (ex == null) {
          runUnderTrace("producer callback") {}
        } else {
          runUnderTrace("producer exception: " + ex) {}
        }
      }
      blockUntilChildSpansFinished(2)
    }


    then:
    // check that the message was received
    def received = records.poll(5, TimeUnit.SECONDS)
    received.value() == greeting
    received.key() == null

    assertTraces(2, SORT_TRACES_BY_ID) {
      trace(3) {
        basicSpan(it, "parent")
        basicSpan(it, "producer callback", span(0))
        producerSpan(it, span(0), false)
      }
      if (hasQueueSpan()) {
        trace(2) {
          consumerSpan(it, trace(1)[1])
          queueSpan(it, trace(0)[2])
        }
      } else {
        trace(1) {
          consumerSpan(it, trace(0)[2])
        }
      }
    }

    def headers = received.headers()
    headers.iterator().hasNext()
    new String(headers.headers("x-datadog-trace-id").iterator().next().value()) == "${TEST_WRITER[0][2].traceId}"
    new String(headers.headers("x-datadog-parent-id").iterator().next().value()) == "${TEST_WRITER[0][2].spanId}"

    cleanup:
    producer.close()
    container?.stop()
  }

  def "test spring kafka template produce and consume"() {
    setup:
    def senderProps = KafkaTestUtils.senderProps(embeddedKafka.getBrokersAsString())
    def producerFactory = new DefaultKafkaProducerFactory<String, String>(senderProps)
    def kafkaTemplate = new KafkaTemplate<String, String>(producerFactory)

    // set up the Kafka consumer properties
    def consumerProperties = KafkaTestUtils.consumerProps("sender", "false", embeddedKafka)

    // create a Kafka consumer factory
    def consumerFactory = new DefaultKafkaConsumerFactory<String, String>(consumerProperties)

    // set the topic that needs to be consumed
    def containerProperties = containerProperties()

    // create a Kafka MessageListenerContainer
    def container = new KafkaMessageListenerContainer<>(consumerFactory, containerProperties)

    // create a thread safe queue to store the received message
    def records = new LinkedBlockingQueue<ConsumerRecord<String, String>>()

    // setup a Kafka message listener
    container.setupMessageListener(new MessageListener<String, String>() {
        @Override
        void onMessage(ConsumerRecord<String, String> record) {
          TEST_WRITER.waitForTraces(1) // ensure consistent ordering of traces
          records.add(record)
        }
      })

    // start the container and underlying message listener
    container.start()

    // wait until the container has the required number of assigned partitions
    ContainerTestUtils.waitForAssignment(container, embeddedKafka.getPartitionsPerTopic())

    when:
    String greeting = "Hello Spring Kafka Sender!"
    runUnderTrace("parent") {
      kafkaTemplate.send(SHARED_TOPIC, greeting).addCallback({
        runUnderTrace("producer callback") {}
      }, { ex ->
        runUnderTrace("producer exception: " + ex) {}
      })
      blockUntilChildSpansFinished(2)
    }

    then:
    // check that the message was received
    def received = records.poll(5, TimeUnit.SECONDS)
    received.value() == greeting
    received.key() == null

    assertTraces(2, SORT_TRACES_BY_ID) {
      trace(3) {
        basicSpan(it, "parent")
        basicSpan(it, "producer callback", span(0))
        producerSpan(it, span(0), false)
      }
      if (hasQueueSpan()) {
        trace(2) {
          consumerSpan(it, trace(1)[1])
          queueSpan(it, trace(0)[2])
        }
      } else {
        trace(1) {
          consumerSpan(it, trace(0)[2])
        }
      }
    }

    def headers = received.headers()
    headers.iterator().hasNext()
    new String(headers.headers("x-datadog-trace-id").iterator().next().value()) == "${TEST_WRITER[0][2].traceId}"
    new String(headers.headers("x-datadog-parent-id").iterator().next().value()) == "${TEST_WRITER[0][2].spanId}"

    cleanup:
    producerFactory.stop()
    container?.stop()
  }

  def "test pass through tombstone"() {
    setup:
    def senderProps = KafkaTestUtils.senderProps(embeddedKafka.getBrokersAsString())
    def producerFactory = new DefaultKafkaProducerFactory<String, String>(senderProps)
    def kafkaTemplate = new KafkaTemplate<String, String>(producerFactory)

    // set up the Kafka consumer properties
    def consumerProperties = KafkaTestUtils.consumerProps("sender", "false", embeddedKafka)

    // create a Kafka consumer factory
    def consumerFactory = new DefaultKafkaConsumerFactory<String, String>(consumerProperties)

    // set the topic that needs to be consumed
    def containerProperties = containerProperties()

    // create a Kafka MessageListenerContainer
    def container = new KafkaMessageListenerContainer<>(consumerFactory, containerProperties)

    // create a thread safe queue to store the received message
    def records = new LinkedBlockingQueue<ConsumerRecord<String, String>>()

    // setup a Kafka message listener
    container.setupMessageListener(new MessageListener<String, String>() {
        @Override
        void onMessage(ConsumerRecord<String, String> record) {
          TEST_WRITER.waitForTraces(1) // ensure consistent ordering of traces
          records.add(record)
        }
      })

    // start the container and underlying message listener
    container.start()

    // wait until the container has the required number of assigned partitions
    ContainerTestUtils.waitForAssignment(container, embeddedKafka.getPartitionsPerTopic())

    when:
    kafkaTemplate.send(SHARED_TOPIC, null)

    then:
    // check that the message was received
    def received = records.poll(5, TimeUnit.SECONDS)
    received.value() == null
    received.key() == null

    assertTraces(2, SORT_TRACES_BY_ID) {
      trace(1) {
        producerSpan(it, null, false, true)
      }
      if (hasQueueSpan()) {
        trace(2) {
          consumerSpan(it, trace(1)[1], 0..0, true)
          queueSpan(it, trace(0)[0])
        }
      } else {
        trace(1) {
          consumerSpan(it, trace(0)[0], 0..0, true)
        }
      }
    }

    cleanup:
    producerFactory.stop()
    container?.stop()
  }

  def "test records(TopicPartition) kafka consume"() {
    setup:
    // set up the Kafka consumer properties
    def kafkaPartition = 0
    def consumerProperties = KafkaTestUtils.consumerProps("sender", "false", embeddedKafka)
    consumerProperties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
    def consumer = new KafkaConsumer<String, String>(consumerProperties)

    def senderProps = KafkaTestUtils.senderProps(embeddedKafka.getBrokersAsString())
    def producer = new KafkaProducer(senderProps)

    consumer.assign(Arrays.asList(new TopicPartition(SHARED_TOPIC, kafkaPartition)))

    when:
    def greeting = "Hello from MockConsumer!"
    producer.send(new ProducerRecord<Integer, String>(SHARED_TOPIC, kafkaPartition, null, greeting))

    then:
    TEST_WRITER.waitForTraces(1)
    def pollResult = KafkaTestUtils.getRecords(consumer)

    def recs = pollResult.records(new TopicPartition(SHARED_TOPIC, kafkaPartition)).iterator()

    def first = null
    if (recs.hasNext()) {
      first = recs.next()
    }

    then:
    recs.hasNext() == false
    first.value() == greeting
    first.key() == null

    assertTraces(2, SORT_TRACES_BY_ID) {
      trace(1) {
        producerSpan(it)
      }
      if (hasQueueSpan()) {
        trace(2) {
          consumerSpan(it, trace(1)[1])
          queueSpan(it, trace(0)[0])
        }
      } else {
        trace(1) {
          consumerSpan(it, trace(0)[0])
        }
      }
    }

    cleanup:
    consumer.close()
    producer.close()

  }

  def "test records(TopicPartition).subList kafka consume"() {
    setup:
    // set up the Kafka consumer properties
    def kafkaPartition = 0
    def consumerProperties = KafkaTestUtils.consumerProps("sender", "false", embeddedKafka)
    consumerProperties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
    def consumer = new KafkaConsumer<String, String>(consumerProperties)

    def senderProps = KafkaTestUtils.senderProps(embeddedKafka.getBrokersAsString())
    def producer = new KafkaProducer(senderProps)

    consumer.assign(Arrays.asList(new TopicPartition(SHARED_TOPIC, kafkaPartition)))

    when:
    def greeting = "Hello from MockConsumer!"
    producer.send(new ProducerRecord<Integer, String>(SHARED_TOPIC, kafkaPartition, null, greeting))

    then:
    TEST_WRITER.waitForTraces(1)
    def pollResult = KafkaTestUtils.getRecords(consumer)

    def records = pollResult.records(new TopicPartition(SHARED_TOPIC, kafkaPartition))
    def recs = records.subList(0, records.size()).iterator()

    def first = null
    if (recs.hasNext()) {
      first = recs.next()
    }

    then:
    recs.hasNext() == false
    first.value() == greeting
    first.key() == null

    assertTraces(2, SORT_TRACES_BY_ID) {
      trace(1) {
        producerSpan(it)
      }
      if (hasQueueSpan()) {
        trace(2) {
          consumerSpan(it, trace(1)[1])
          queueSpan(it, trace(0)[0])
        }
      } else {
        trace(1) {
          consumerSpan(it, trace(0)[0])
        }
      }
    }

    cleanup:
    consumer.close()
    producer.close()

  }

  @Requires({ jvm.java8Compatible })
  def "test records(TopicPartition).forEach kafka consume"() {
    setup:
    // set up the Kafka consumer properties
    def kafkaPartition = 0
    def consumerProperties = KafkaTestUtils.consumerProps("sender", "false", embeddedKafka)
    consumerProperties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
    def consumer = new KafkaConsumer<String, String>(consumerProperties)

    def senderProps = KafkaTestUtils.senderProps(embeddedKafka.getBrokersAsString())
    def producer = new KafkaProducer(senderProps)

    consumer.assign(Arrays.asList(new TopicPartition(SHARED_TOPIC, kafkaPartition)))

    when:
    def greeting = "Hello from MockConsumer!"
    producer.send(new ProducerRecord<Integer, String>(SHARED_TOPIC, kafkaPartition, null, greeting))

    then:
    TEST_WRITER.waitForTraces(1)
    def pollResult = KafkaTestUtils.getRecords(consumer)

    def records = pollResult.records(new TopicPartition(SHARED_TOPIC, kafkaPartition))

    def last = null
    records.forEach {
      last = it
      assert activeSpan() != null
    }

    then:
    records.size() == 1
    last.value() == greeting
    last.key() == null

    assertTraces(2, SORT_TRACES_BY_ID) {
      trace(1) {
        producerSpan(it)
      }
      if (hasQueueSpan()) {
        trace(2) {
          consumerSpan(it, trace(1)[1])
          queueSpan(it, trace(0)[0])
        }
      } else {
        trace(1) {
          consumerSpan(it, trace(0)[0])
        }
      }
    }

    cleanup:
    consumer.close()
    producer.close()

  }

  def "test iteration backwards over ConsumerRecords"() {
    setup:
    def kafkaPartition = 0
    def consumerProperties = KafkaTestUtils.consumerProps("sender", "false", embeddedKafka)
    consumerProperties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
    def consumer = new KafkaConsumer<String, String>(consumerProperties)

    def senderProps = KafkaTestUtils.senderProps(embeddedKafka.getBrokersAsString())
    def producer = new KafkaProducer(senderProps)

    consumer.assign(Arrays.asList(new TopicPartition(SHARED_TOPIC, kafkaPartition)))

    when:
    def greetings = ["msg 1", "msg 2", "msg 3"]
    greetings.each {
      producer.send(new ProducerRecord<Integer, String>(SHARED_TOPIC, kafkaPartition, null, it))
    }

    then:
    TEST_WRITER.waitForTraces(3)
    def pollRecords = KafkaTestUtils.getRecords(consumer)

    def listIter =
      pollRecords.records(new TopicPartition(SHARED_TOPIC, kafkaPartition)).listIterator()

    then:
    def receivedSet = greetings.toSet()
    while (listIter.hasNext()) {
      listIter.next()
    }
    while (listIter.hasPrevious()) {
      def record = listIter.previous()
      receivedSet.remove(record.value())
      assert record.key() == null
    }
    receivedSet.isEmpty()

    assertTraces(9, SORT_TRACES_BY_ID) {

      // producing traces
      trace(1) {
        producerSpan(it)
      }
      trace(1) {
        producerSpan(it)
      }
      trace(1) {
        producerSpan(it)
      }

      // iterating to the end of ListIterator:
      if (hasQueueSpan()) {
        trace(2) {
          consumerSpan(it, trace(3)[1], 0..0)
          queueSpan(it, trace(0)[0])
        }
        trace(2) {
          consumerSpan(it, trace(4)[1], 1..1)
          queueSpan(it, trace(1)[0])
        }
        trace(2) {
          consumerSpan(it, trace(5)[1], 2..2)
          queueSpan(it, trace(2)[0])
        }
      } else {
        trace(1) {
          consumerSpan(it, trace(0)[0], 0..0)
        }
        trace(1) {
          consumerSpan(it, trace(1)[0], 1..1)
        }
        trace(1) {
          consumerSpan(it, trace(2)[0], 2..2)
        }
      }

      // backwards iteration over ListIterator to the beginning
      if (hasQueueSpan()) {
        trace(2) {
          consumerSpan(it, trace(6)[1], 2..2)
          queueSpan(it, trace(2)[0])
        }
        trace(2) {
          consumerSpan(it, trace(7)[1], 1..1)
          queueSpan(it, trace(1)[0])
        }
        trace(2) {
          consumerSpan(it, trace(8)[1], 0..0)
          queueSpan(it, trace(0)[0])
        }
      } else {
        trace(1) {
          consumerSpan(it, trace(2)[0], 2..2)
        }
        trace(1) {
          consumerSpan(it, trace(1)[0], 1..1)
        }
        trace(1) {
          consumerSpan(it, trace(0)[0], 0..0)
        }
      }
    }

    cleanup:
    consumer.close()
    producer.close()

  }

  def "test spring kafka template produce and batch consume"() {
    setup:
    def senderProps = KafkaTestUtils.senderProps(embeddedKafka.getBrokersAsString())
    def producerFactory = new DefaultKafkaProducerFactory<String, String>(senderProps)
    def kafkaTemplate = new KafkaTemplate<String, String>(producerFactory)

    def consumerProperties = KafkaTestUtils.consumerProps("sender", "false", embeddedKafka)
    def consumerFactory = new DefaultKafkaConsumerFactory<String, String>(consumerProperties)
    def containerProperties = containerProperties()


    def container = new KafkaMessageListenerContainer<>(consumerFactory, containerProperties)
    def records = new LinkedBlockingQueue<ConsumerRecord<String, String>>()
    container.setupMessageListener(new BatchMessageListener<String, String>() {
        @Override
        void onMessage(List<ConsumerRecord<String, String>> consumerRecords) {
          TEST_WRITER.waitForTraces(1) // ensure consistent ordering of traces
          consumerRecords.each {
            records.add(it)
          }
        }
      })
    container.start()
    ContainerTestUtils.waitForAssignment(container, embeddedKafka.getPartitionsPerTopic())

    when:
    List<String> greetings = ["msg 1", "msg 2", "msg 3"]
    runUnderTrace("parent") {
      for (g in greetings) {
        kafkaTemplate.send(SHARED_TOPIC, g).addCallback({
          runUnderTrace("producer callback") {}
        }, { ex ->
          runUnderTrace("producer exception: " + ex) {}
        })
      }
      blockUntilChildSpansFinished(2 * greetings.size())
    }

    then:
    def receivedSet = greetings.toSet()
    greetings.eachWithIndex { g, i ->
      def received = records.poll(5, TimeUnit.SECONDS)
      receivedSet.remove(received.value()) //maybe received out of order in case several partitions
      assert received.key() == null

      def headers = received.headers()
      assert headers.iterator().hasNext()

    }
    assert receivedSet.isEmpty()

    assertTraces(4, SORT_TRACES_BY_ID) {
      trace(7) {
        basicSpan(it, "parent")
        basicSpan(it, "producer callback", span(0))
        producerSpan(it, span(0), false)
        basicSpan(it, "producer callback", span(0))
        producerSpan(it, span(0), false)
        basicSpan(it, "producer callback", span(0))
        producerSpan(it, span(0), false)
      }

      if (hasQueueSpan()) {
        trace(2) {
          consumerSpan(it, trace(1)[1], 0..0)
          queueSpan(it, trace(0)[6])
        }
        trace(2) {
          consumerSpan(it, trace(2)[1], 0..1)
          queueSpan(it, trace(0)[4])
        }
        trace(2) {
          consumerSpan(it, trace(3)[1], 0..1)
          queueSpan(it, trace(0)[2])
        }
      } else {
        trace(1) {
          consumerSpan(it, trace(0)[6], 0..0)
        }
        trace(1) {
          consumerSpan(it, trace(0)[4], 0..1)
        }
        trace(1) {
          consumerSpan(it, trace(0)[2], 0..1)
        }
      }
    }

    cleanup:
    producerFactory.stop()
    container?.stop()
  }

  @Unroll
  def "test kafka client header propagation manual config"() {
    setup:
    def senderProps = KafkaTestUtils.senderProps(embeddedKafka.getBrokersAsString())
    def producerFactory = new DefaultKafkaProducerFactory<String, String>(senderProps)
    def kafkaTemplate = new KafkaTemplate<String, String>(producerFactory)

    // set up the Kafka consumer properties
    def consumerProperties = KafkaTestUtils.consumerProps("sender", "false", embeddedKafka)

    // create a Kafka consumer factory
    def consumerFactory = new DefaultKafkaConsumerFactory<String, String>(consumerProperties)

    // set the topic that needs to be consumed
    def containerProperties = containerProperties()

    // create a Kafka MessageListenerContainer
    def container = new KafkaMessageListenerContainer<>(consumerFactory, containerProperties)

    // create a thread safe queue to store the received message
    def records = new LinkedBlockingQueue<ConsumerRecord<String, String>>()

    // setup a Kafka message listener
    container.setupMessageListener(new MessageListener<String, String>() {
        @Override
        void onMessage(ConsumerRecord<String, String> record) {
          TEST_WRITER.waitForTraces(1) // ensure consistent ordering of traces
          records.add(record)
        }
      })

    // start the container and underlying message listener
    container.start()

    // wait until the container has the required number of assigned partitions
    ContainerTestUtils.waitForAssignment(container, embeddedKafka.getPartitionsPerTopic())

    when:
    String message = "Testing without headers"
    injectSysConfig("kafka.client.propagation.enabled", value)
    kafkaTemplate.send(SHARED_TOPIC, message)

    then:
    // check that the message was received
    def received = records.poll(5, TimeUnit.SECONDS)

    received.headers().iterator().hasNext() == expected

    cleanup:
    producerFactory.stop()
    container?.stop()

    where:
    value                                                    | expected
    "false"                                                  | false
    "true"                                                   | true
  }

  def containerProperties() {
    try {
      // Different class names for test and latestDepTest.
      return Class.forName("org.springframework.kafka.listener.config.ContainerProperties").newInstance(SHARED_TOPIC)
    } catch (ClassNotFoundException | NoClassDefFoundError e) {
      return Class.forName("org.springframework.kafka.listener.ContainerProperties").newInstance(SHARED_TOPIC)
    }
  }

  def producerSpan(
    TraceAssert trace,
    DDSpan parentSpan = null,
    boolean partitioned = true,
    boolean tombstone = false
  ) {
    trace.span {
      serviceName hasQueueSpan() ? expectedServiceName() : "kafka"
      operationName "kafka.produce"
      resourceName "Produce Topic $SHARED_TOPIC"
      spanType "queue"
      errored false
      measured true
      if (parentSpan) {
        childOf parentSpan
      } else {
        parent()
      }
      tags {
        "$Tags.COMPONENT" "java-kafka"
        "$Tags.SPAN_KIND" Tags.SPAN_KIND_PRODUCER
        if (partitioned) {
          "$InstrumentationTags.PARTITION" { it >= 0 }
        }
        if (tombstone) {
          "$InstrumentationTags.TOMBSTONE" true
        }
        defaultTags()
      }
    }
  }

  def queueSpan(
    TraceAssert trace,
    DDSpan parentSpan = null
  ) {
    trace.span {
      serviceName splitByDestination() ? "$SHARED_TOPIC" : "kafka"
      operationName "kafka.deliver"
      resourceName "$SHARED_TOPIC"
      spanType "queue"
      errored false
      measured true
      if (parentSpan) {
        childOf parentSpan
      } else {
        parent()
      }
      tags {
        "$Tags.COMPONENT" "java-kafka"
        "$Tags.SPAN_KIND" Tags.SPAN_KIND_BROKER
        defaultTags(true)
      }
    }
  }

  def consumerSpan(
    TraceAssert trace,
    DDSpan parentSpan = null,
    Range offset = 0..0,
    boolean tombstone = false,
    boolean distributedRootSpan = !hasQueueSpan()
  ) {
    trace.span {
      serviceName hasQueueSpan() ? expectedServiceName() : "kafka"
      operationName "kafka.consume"
      resourceName "Consume Topic $SHARED_TOPIC"
      spanType "queue"
      errored false
      measured true
      if (parentSpan) {
        childOf parentSpan
      } else {
        parent()
      }
      tags {
        "$Tags.COMPONENT" "java-kafka"
        "$Tags.SPAN_KIND" Tags.SPAN_KIND_CONSUMER
        "$InstrumentationTags.PARTITION" { it >= 0 }
        "$InstrumentationTags.OFFSET" { offset.containsWithinBounds(it as int) }
        "$InstrumentationTags.RECORD_QUEUE_TIME_MS" { it >= 0 }
        "$InstrumentationTags.RECORD_END_TO_END_DURATION_MS" { it >= 0 }
        if (tombstone) {
          "$InstrumentationTags.TOMBSTONE" true
        }
        defaultTags(distributedRootSpan)
      }
    }
  }
}

class KafkaClientForkedTest extends KafkaClientTestBase {
  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig("dd.service", "KafkaClientTest")
    injectSysConfig("dd.kafka.legacy.tracing.enabled", "false")
  }

  @Override
  String expectedServiceName()  {
    return "KafkaClientTest"
  }

  @Override
  boolean hasQueueSpan() {
    return true
  }

  @Override
  boolean splitByDestination() {
    return false
  }
}

class KafkaClientSplitByDestinationForkedTest extends KafkaClientTestBase {
  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig("dd.service", "KafkaClientTest")
    injectSysConfig("dd.kafka.legacy.tracing.enabled", "false")
    injectSysConfig("dd.message.broker.split-by-destination", "true")
  }

  @Override
  String expectedServiceName()  {
    return "KafkaClientTest"
  }

  @Override
  boolean hasQueueSpan() {
    return true
  }

  @Override
  boolean splitByDestination() {
    return true
  }
}

class KafkaClientLegacyTracingForkedTest extends KafkaClientTestBase {
  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig("dd.kafka.legacy.tracing.enabled", "true")
  }

  @Override
  String expectedServiceName() {
    return "kafka"
  }

  @Override
  boolean hasQueueSpan() {
    return false
  }

  @Override
  boolean splitByDestination() {
    return false
  }
}

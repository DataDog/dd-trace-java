import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.DDTags
import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.core.datastreams.StatsGroup
import datadog.trace.test.util.Flaky
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.Producer
import org.apache.kafka.clients.producer.ProducerConfig
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
import org.springframework.kafka.test.EmbeddedKafkaBroker
import org.springframework.kafka.test.rule.EmbeddedKafkaRule
import org.springframework.kafka.test.utils.ContainerTestUtils
import org.springframework.kafka.test.utils.KafkaTestUtils
import spock.lang.Unroll
import spock.util.concurrent.PollingConditions

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

import static datadog.trace.agent.test.utils.TraceUtils.basicSpan
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeScope

@Flaky("https://github.com/DataDog/dd-trace-java/issues/3864")
class KafkaClientTest extends AgentTestRunner {
  static final SHARED_TOPIC = "shared.topic"

  @Override
  protected boolean isDataStreamsEnabled() {
    return true
  }

  @Override
  boolean useStrictTraceWrites() {
    // TODO fix this by making sure that spans get closed properly
    return false
  }

  @Rule
  EmbeddedKafkaRule kafkaRule = new EmbeddedKafkaRule(1, true, SHARED_TOPIC)
  EmbeddedKafkaBroker embeddedKafka = kafkaRule.embeddedKafka

  @Override
  void configurePreAgent() {
    super.configurePreAgent()

    injectSysConfig("dd.kafka.e2e.duration.enabled", "true")
  }

  def "test kafka produce and consume"() {
    setup:
    def producerProps = KafkaTestUtils.producerProps(embeddedKafka.getBrokersAsString())
    Producer<String, String> producer = new KafkaProducer<>(producerProps, new StringSerializer(), new StringSerializer())

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
    TEST_DATA_STREAMS_WRITER.waitForGroups(2)

    then:
    // check that the message was received
    def received = records.poll(5, TimeUnit.SECONDS)
    received.value() == greeting
    received.key() == null

    assertTraces(2) {
      trace(3) {
        basicSpan(it, "parent")
        basicSpan(it, "producer callback", span(0))
        span {
          serviceName "kafka"
          operationName "kafka.produce"
          resourceName "Produce Topic $SHARED_TOPIC"
          spanType "queue"
          errored false
          childOf span(0)
          tags {
            "$Tags.COMPONENT" "java-kafka"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_PRODUCER
            if ({ isDataStreamsEnabled() }) {
              "$DDTags.PATHWAY_HASH" { String }
            }
            "$InstrumentationTags.KAFKA_BOOTSTRAP_SERVERS" producerProps.get(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG)
            peerServiceFrom(InstrumentationTags.KAFKA_BOOTSTRAP_SERVERS)
            defaultTags()
          }
        }
      }
      trace(1) {
        // CONSUMER span 0
        span {
          serviceName "kafka"
          operationName "kafka.consume"
          resourceName "Consume Topic $SHARED_TOPIC"
          spanType "queue"
          errored false
          childOf trace(0)[2]
          tags {
            "$Tags.COMPONENT" "java-kafka"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CONSUMER
            "$InstrumentationTags.PARTITION" { it >= 0 }
            "$InstrumentationTags.OFFSET" 0
            "$InstrumentationTags.CONSUMER_GROUP" "sender"
            "$InstrumentationTags.KAFKA_BOOTSTRAP_SERVERS" consumerProperties.get(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG)
            "$InstrumentationTags.RECORD_QUEUE_TIME_MS" { it >= 0 }
            // TODO - test with and without feature enabled once Config is easier to control
            "$InstrumentationTags.RECORD_END_TO_END_DURATION_MS" { it >= 0 }
            if ({ isDataStreamsEnabled() }) {
              "$DDTags.PATHWAY_HASH" { String }
            }
            defaultTags(true)
          }
        }
      }
    }

    def headers = received.headers()
    headers.iterator().hasNext()
    new String(headers.headers("x-datadog-trace-id").iterator().next().value()) == "${TEST_WRITER[0][2].traceId}"
    new String(headers.headers("x-datadog-parent-id").iterator().next().value()) == "${TEST_WRITER[0][2].spanId}"

    String expectedKafkaClusterId = producer.metadata.fetch().clusterResource().clusterId()

    StatsGroup first = TEST_DATA_STREAMS_WRITER.groups.find { it.parentHash == 0 }
    verifyAll(first) {
      edgeTags == [
        "direction:out",
        "kafka_cluster_id:" + expectedKafkaClusterId,
        "topic:$SHARED_TOPIC".toString(),
        "type:kafka"
      ]
      edgeTags.size() == 4
      payloadSize.minValue > 0.0
    }

    StatsGroup second = TEST_DATA_STREAMS_WRITER.groups.find { it.parentHash == first.hash }
    verifyAll(second) {
      edgeTags == [
        "direction:in",
        "group:sender",
        "kafka_cluster_id:" + expectedKafkaClusterId,
        "topic:$SHARED_TOPIC".toString(),
        "type:kafka"
      ]
      edgeTags.size() == 5
      payloadSize.minValue > 0.0
    }

    cleanup:
    producer.close()
    container?.stop()
  }

  def "test spring kafka template produce and consume"() {
    setup:
    def producerProps = KafkaTestUtils.producerProps(embeddedKafka.getBrokersAsString())
    producerProps.put(ProducerConfig.METADATA_MAX_AGE_CONFIG, 1000)
    def producerFactory = new DefaultKafkaProducerFactory<String, String>(producerProps)
    def kafkaTemplate = new KafkaTemplate<String, String>(producerFactory)
    String clusterId = waitForKafkaMetadataUpdate(kafkaTemplate)

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
    TEST_DATA_STREAMS_WRITER.waitForGroups(2)

    then:
    // check that the message was received
    def received = records.poll(5, TimeUnit.SECONDS)
    received.value() == greeting
    received.key() == null

    assertTraces(2) {
      trace(3) {
        basicSpan(it, "parent")
        basicSpan(it, "producer callback", span(0))
        span {
          serviceName "kafka"
          operationName "kafka.produce"
          resourceName "Produce Topic $SHARED_TOPIC"
          spanType "queue"
          errored false
          childOf span(0)
          tags {
            "$Tags.COMPONENT" "java-kafka"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_PRODUCER
            if ({ isDataStreamsEnabled()}) {
              "$DDTags.PATHWAY_HASH" { String }
            }
            "$InstrumentationTags.KAFKA_BOOTSTRAP_SERVERS" producerProps.get(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG)
            peerServiceFrom(InstrumentationTags.KAFKA_BOOTSTRAP_SERVERS)
            defaultTags()
          }
        }
      }
      trace(1) {
        // CONSUMER span 0
        span {
          serviceName "kafka"
          operationName "kafka.consume"
          resourceName "Consume Topic $SHARED_TOPIC"
          spanType "queue"
          errored false
          childOf trace(0)[2]
          tags {
            "$Tags.COMPONENT" "java-kafka"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CONSUMER
            "$InstrumentationTags.PARTITION" { it >= 0 }
            "$InstrumentationTags.OFFSET" 0
            "$InstrumentationTags.CONSUMER_GROUP" "sender"
            "$InstrumentationTags.KAFKA_BOOTSTRAP_SERVERS" consumerProperties.get(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG)
            "$InstrumentationTags.RECORD_QUEUE_TIME_MS" { it >= 0 }
            // TODO - test with and without feature enabled once Config is easier to control
            "$InstrumentationTags.RECORD_END_TO_END_DURATION_MS" { it >= 0 }
            if ({ isDataStreamsEnabled()}) {
              "$DDTags.PATHWAY_HASH" { String }
            }
            defaultTags(true)
          }
        }
      }
    }

    def headers = received.headers()
    headers.iterator().hasNext()
    new String(headers.headers("x-datadog-trace-id").iterator().next().value()) == "${TEST_WRITER[0][2].traceId}"
    new String(headers.headers("x-datadog-parent-id").iterator().next().value()) == "${TEST_WRITER[0][2].spanId}"

    StatsGroup first = TEST_DATA_STREAMS_WRITER.groups.find { it.parentHash == 0 }
    verifyAll(first) {
      edgeTags == [
        "direction:out",
        "kafka_cluster_id:$clusterId",
        "topic:$SHARED_TOPIC".toString(),
        "type:kafka"
      ]
      edgeTags.size() == 4
      payloadSize.minValue > 0.0
    }

    StatsGroup second = TEST_DATA_STREAMS_WRITER.groups.find { it.parentHash == first.hash }
    verifyAll(second) {
      edgeTags == [
        "direction:in",
        "group:sender",
        "kafka_cluster_id:$clusterId",
        "topic:$SHARED_TOPIC".toString(),
        "type:kafka"
      ]
      edgeTags.size() == 5
      payloadSize.minValue > 0.0
    }

    cleanup:
    producerFactory.destroy()
    container?.stop()
  }

  def "test pass through tombstone"() {
    setup:
    def producerProps = KafkaTestUtils.producerProps(embeddedKafka.getBrokersAsString())
    def producerFactory = new DefaultKafkaProducerFactory<String, String>(producerProps)
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

    assertTraces(2) {
      trace(1) {
        // PRODUCER span 0
        span {
          serviceName "kafka"
          operationName "kafka.produce"
          resourceName "Produce Topic $SHARED_TOPIC"
          spanType "queue"
          errored false
          parent()
          tags {
            "$Tags.COMPONENT" "java-kafka"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_PRODUCER
            "$InstrumentationTags.TOMBSTONE" true
            if ({ isDataStreamsEnabled()}) {
              "$DDTags.PATHWAY_HASH" { String }
            }
            "$InstrumentationTags.KAFKA_BOOTSTRAP_SERVERS" producerProps.get(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG)
            peerServiceFrom(InstrumentationTags.KAFKA_BOOTSTRAP_SERVERS)
            defaultTags()
          }
        }
      }
      trace(1) {
        // CONSUMER span 0
        span {
          serviceName "kafka"
          operationName "kafka.consume"
          resourceName "Consume Topic $SHARED_TOPIC"
          spanType "queue"
          errored false
          childOf trace(0)[0]
          tags {
            "$Tags.COMPONENT" "java-kafka"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CONSUMER
            "$InstrumentationTags.PARTITION" { it >= 0 }
            "$InstrumentationTags.OFFSET" 0
            "$InstrumentationTags.CONSUMER_GROUP" "sender"
            "$InstrumentationTags.KAFKA_BOOTSTRAP_SERVERS" consumerProperties.get(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG)
            "$InstrumentationTags.RECORD_QUEUE_TIME_MS" { it >= 0 }
            "$InstrumentationTags.TOMBSTONE" true
            // TODO - test with and without feature enabled once Config is easier to control
            "$InstrumentationTags.RECORD_END_TO_END_DURATION_MS" { it >= 0 }
            if ({ isDataStreamsEnabled()}) {
              "$DDTags.PATHWAY_HASH" { String }
            }
            defaultTags(true)
          }
        }
      }
    }

    cleanup:
    producerFactory.destroy()
    container?.stop()
  }

  def "test records(TopicPartition) kafka consume"() {
    setup:

    // set up the Kafka consumer properties
    def kafkaPartition = 0
    def consumerProperties = KafkaTestUtils.consumerProps("sender", "false", embeddedKafka)
    consumerProperties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
    def consumer = new KafkaConsumer<String, String>(consumerProperties)

    def producerProps = KafkaTestUtils.producerProps(embeddedKafka.getBrokersAsString())
    def producer = new KafkaProducer(producerProps)

    consumer.assign(Arrays.asList(new TopicPartition(SHARED_TOPIC, kafkaPartition)))

    when:
    def greeting = "Hello from MockConsumer!"
    producer.send(new ProducerRecord<Integer, String>(SHARED_TOPIC, kafkaPartition, null, greeting))

    then:
    TEST_WRITER.waitForTraces(1)
    def records = new LinkedBlockingQueue<ConsumerRecord<String, String>>()
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

    assertTraces(2) {
      trace(1) {
        // PRODUCER span 0
        span {
          serviceName "kafka"
          operationName "kafka.produce"
          resourceName "Produce Topic $SHARED_TOPIC"
          spanType "queue"
          errored false
          parent()
          tags {
            "$Tags.COMPONENT" "java-kafka"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_PRODUCER
            "$InstrumentationTags.PARTITION" { it >= 0 }
            if ({ isDataStreamsEnabled()}) {
              "$DDTags.PATHWAY_HASH" { String }
            }
            "$InstrumentationTags.KAFKA_BOOTSTRAP_SERVERS" producerProps.get(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG)
            peerServiceFrom(InstrumentationTags.KAFKA_BOOTSTRAP_SERVERS)
            defaultTags(true)
          }
        }
      }
      trace(1) {
        // CONSUMER span 0
        span {
          serviceName "kafka"
          operationName "kafka.consume"
          resourceName "Consume Topic $SHARED_TOPIC"
          spanType "queue"
          errored false
          childOf trace(0)[0]
          tags {
            "$Tags.COMPONENT" "java-kafka"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CONSUMER
            "$InstrumentationTags.PARTITION" { it >= 0 }
            "$InstrumentationTags.OFFSET" 0
            "$InstrumentationTags.CONSUMER_GROUP" "sender"
            "$InstrumentationTags.KAFKA_BOOTSTRAP_SERVERS" consumerProperties.get(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG)
            "$InstrumentationTags.RECORD_QUEUE_TIME_MS" { it >= 0 }
            // TODO - test with and without feature enabled once Config is easier to control
            "$InstrumentationTags.RECORD_END_TO_END_DURATION_MS" { it >= 0 }
            if ({ isDataStreamsEnabled()}) {
              "$DDTags.PATHWAY_HASH" { String }
            }
            defaultTags(true)
          }
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

    def producerProps = KafkaTestUtils.producerProps(embeddedKafka.getBrokersAsString())
    def producer = new KafkaProducer(producerProps)

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

    assertTraces(9) {
      // producing traces
      trace(1) {
        span {
          serviceName "kafka"
          operationName "kafka.produce"
          resourceName "Produce Topic $SHARED_TOPIC"
          spanType "queue"
          errored false
          parent()
          tags {
            "$Tags.COMPONENT" "java-kafka"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_PRODUCER
            "$InstrumentationTags.PARTITION" { it >= 0 }
            if ({ isDataStreamsEnabled()}) {
              "$DDTags.PATHWAY_HASH" { String }
            }
            "$InstrumentationTags.KAFKA_BOOTSTRAP_SERVERS" producerProps.get(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG)
            peerServiceFrom(InstrumentationTags.KAFKA_BOOTSTRAP_SERVERS)
            defaultTags(true)
          }
        }
      }
      trace(1) {
        span {
          serviceName "kafka"
          operationName "kafka.produce"
          resourceName "Produce Topic $SHARED_TOPIC"
          spanType "queue"
          errored false
          parent()
          tags {
            "$Tags.COMPONENT" "java-kafka"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_PRODUCER
            "$InstrumentationTags.PARTITION" { it >= 0 }
            if ({ isDataStreamsEnabled()}) {
              "$DDTags.PATHWAY_HASH" { String }
            }
            "$InstrumentationTags.KAFKA_BOOTSTRAP_SERVERS" producerProps.get(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG)
            peerServiceFrom(InstrumentationTags.KAFKA_BOOTSTRAP_SERVERS)
            defaultTags(true)
          }
        }
      }
      trace(1) {
        span {
          serviceName "kafka"
          operationName "kafka.produce"
          resourceName "Produce Topic $SHARED_TOPIC"
          spanType "queue"
          errored false
          parent()
          tags {
            "$Tags.COMPONENT" "java-kafka"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_PRODUCER
            "$InstrumentationTags.PARTITION" { it >= 0 }
            if ({ isDataStreamsEnabled()}) {
              "$DDTags.PATHWAY_HASH" { String }
            }
            "$InstrumentationTags.KAFKA_BOOTSTRAP_SERVERS" producerProps.get(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG)
            peerServiceFrom(InstrumentationTags.KAFKA_BOOTSTRAP_SERVERS)
            defaultTags(true)
          }
        }
      }

      // iterating to the end of ListIterator:
      trace(1) {
        span {
          serviceName "kafka"
          operationName "kafka.consume"
          resourceName "Consume Topic $SHARED_TOPIC"
          spanType "queue"
          errored false
          childOf trace(0)[0]
          tags {
            "$Tags.COMPONENT" "java-kafka"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CONSUMER
            "$InstrumentationTags.PARTITION" { it >= 0 }
            "$InstrumentationTags.OFFSET" 0
            "$InstrumentationTags.CONSUMER_GROUP" "sender"
            "$InstrumentationTags.KAFKA_BOOTSTRAP_SERVERS" consumerProperties.get(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG)
            "$InstrumentationTags.RECORD_QUEUE_TIME_MS" { it >= 0 }
            "$InstrumentationTags.RECORD_END_TO_END_DURATION_MS" { it >= 0 }
            if ({ isDataStreamsEnabled()}) {
              "$DDTags.PATHWAY_HASH" { String }
            }
            defaultTags(true)
          }
        }
      }
      trace(1) {
        span {
          serviceName "kafka"
          operationName "kafka.consume"
          resourceName "Consume Topic $SHARED_TOPIC"
          spanType "queue"
          errored false
          childOf trace(1)[0]
          tags {
            "$Tags.COMPONENT" "java-kafka"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CONSUMER
            "$InstrumentationTags.PARTITION" { it >= 0 }
            "$InstrumentationTags.OFFSET" 1
            "$InstrumentationTags.CONSUMER_GROUP" "sender"
            "$InstrumentationTags.KAFKA_BOOTSTRAP_SERVERS" consumerProperties.get(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG)
            "$InstrumentationTags.RECORD_QUEUE_TIME_MS" { it >= 0 }
            "$InstrumentationTags.RECORD_END_TO_END_DURATION_MS" { it >= 0 }
            if ({ isDataStreamsEnabled()}) {
              "$DDTags.PATHWAY_HASH" { String }
            }
            defaultTags(true)
          }
        }
      }
      trace(1) {
        span {
          serviceName "kafka"
          operationName "kafka.consume"
          resourceName "Consume Topic $SHARED_TOPIC"
          spanType "queue"
          errored false
          childOf trace(2)[0]
          tags {
            "$Tags.COMPONENT" "java-kafka"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CONSUMER
            "$InstrumentationTags.PARTITION" { it >= 0 }
            "$InstrumentationTags.OFFSET" 2
            "$InstrumentationTags.CONSUMER_GROUP" "sender"
            "$InstrumentationTags.KAFKA_BOOTSTRAP_SERVERS" consumerProperties.get(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG)
            "$InstrumentationTags.RECORD_QUEUE_TIME_MS" { it >= 0 }
            "$InstrumentationTags.RECORD_END_TO_END_DURATION_MS" { it >= 0 }
            if ({ isDataStreamsEnabled()}) {
              "$DDTags.PATHWAY_HASH" { String }
            }
            defaultTags(true)
          }
        }
      }

      // backwards iteration over ListIterator to the beginning
      trace(1) {
        span {
          serviceName "kafka"
          operationName "kafka.consume"
          resourceName "Consume Topic $SHARED_TOPIC"
          spanType "queue"
          errored false
          childOf trace(2)[0]
          tags {
            "$Tags.COMPONENT" "java-kafka"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CONSUMER
            "$InstrumentationTags.PARTITION" { it >= 0 }
            "$InstrumentationTags.OFFSET" 2
            "$InstrumentationTags.CONSUMER_GROUP" "sender"
            "$InstrumentationTags.KAFKA_BOOTSTRAP_SERVERS" consumerProperties.get(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG)
            "$InstrumentationTags.RECORD_QUEUE_TIME_MS" { it >= 0 }
            "$InstrumentationTags.RECORD_END_TO_END_DURATION_MS" { it >= 0 }
            if ({ isDataStreamsEnabled()}) {
              "$DDTags.PATHWAY_HASH" { String }
            }
            defaultTags(true)
          }
        }
      }
      trace(1) {
        span {
          serviceName "kafka"
          operationName "kafka.consume"
          resourceName "Consume Topic $SHARED_TOPIC"
          spanType "queue"
          errored false
          childOf trace(1)[0]
          tags {
            "$Tags.COMPONENT" "java-kafka"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CONSUMER
            "$InstrumentationTags.PARTITION" { it >= 0 }
            "$InstrumentationTags.OFFSET" 1
            "$InstrumentationTags.CONSUMER_GROUP" "sender"
            "$InstrumentationTags.KAFKA_BOOTSTRAP_SERVERS" consumerProperties.get(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG)
            "$InstrumentationTags.RECORD_QUEUE_TIME_MS" { it >= 0 }
            "$InstrumentationTags.RECORD_END_TO_END_DURATION_MS" { it >= 0 }
            if ({ isDataStreamsEnabled()}) {
              "$DDTags.PATHWAY_HASH" { String }
            }
            defaultTags(true)
          }
        }
      }
      trace(1) {
        span {
          serviceName "kafka"
          operationName "kafka.consume"
          resourceName "Consume Topic $SHARED_TOPIC"
          spanType "queue"
          errored false
          childOf trace(0)[0]
          tags {
            "$Tags.COMPONENT" "java-kafka"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CONSUMER
            "$InstrumentationTags.PARTITION" { it >= 0 }
            "$InstrumentationTags.OFFSET" 0
            "$InstrumentationTags.CONSUMER_GROUP" "sender"
            "$InstrumentationTags.KAFKA_BOOTSTRAP_SERVERS" consumerProperties.get(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG)
            "$InstrumentationTags.RECORD_QUEUE_TIME_MS" { it >= 0 }
            "$InstrumentationTags.RECORD_END_TO_END_DURATION_MS" { it >= 0 }
            if ({ isDataStreamsEnabled()}) {
              "$DDTags.PATHWAY_HASH" { String }
            }
            defaultTags(true)
          }
        }
      }
    }

    cleanup:
    consumer.close()
    producer.close()

  }

  def "test spring kafka template produce and batch consume"() {
    setup:
    def conditions = new PollingConditions(timeout: 10)
    def producerProps = KafkaTestUtils.producerProps(embeddedKafka.getBrokersAsString())
    producerProps.put(ProducerConfig.METADATA_MAX_AGE_CONFIG, 1000)
    def producerFactory = new DefaultKafkaProducerFactory<String, String>(producerProps)
    def kafkaTemplate = new KafkaTemplate<String, String>(producerFactory)
    String clusterId = waitForKafkaMetadataUpdate(kafkaTemplate)

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
    TEST_DATA_STREAMS_WRITER.waitForGroups(2)

    then:
    conditions.eventually {
      assert !records.isEmpty()
    }
    def receivedSet = greetings.toSet()
    greetings.eachWithIndex { g, i ->
      def received = records.poll(5, TimeUnit.SECONDS)
      receivedSet.remove(received.value()) //maybe received out of order in case several partitions
      assert received.key() == null

      def headers = received.headers()
      assert headers.iterator().hasNext()

    }
    assert receivedSet.isEmpty()

    assertTraces(4) {
      trace(7) {
        basicSpan(it, "parent")
        basicSpan(it, "producer callback", span(0))
        span {
          serviceName "kafka"
          operationName "kafka.produce"
          resourceName "Produce Topic $SHARED_TOPIC"
          spanType "queue"
          errored false
          childOf span(0)
          tags {
            "$Tags.COMPONENT" "java-kafka"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_PRODUCER
            if ({ isDataStreamsEnabled()}) {
              "$DDTags.PATHWAY_HASH" { String }
            }
            "$InstrumentationTags.KAFKA_BOOTSTRAP_SERVERS" producerProps.get(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG)
            peerServiceFrom(InstrumentationTags.KAFKA_BOOTSTRAP_SERVERS)
            defaultTags()
          }
        }
        basicSpan(it, "producer callback", span(0))
        span {
          serviceName "kafka"
          operationName "kafka.produce"
          resourceName "Produce Topic $SHARED_TOPIC"
          spanType "queue"
          errored false
          childOf span(0)
          tags {
            "$Tags.COMPONENT" "java-kafka"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_PRODUCER
            if ({ isDataStreamsEnabled()}) {
              "$DDTags.PATHWAY_HASH" { String }
            }
            "$InstrumentationTags.KAFKA_BOOTSTRAP_SERVERS" producerProps.get(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG)
            peerServiceFrom(InstrumentationTags.KAFKA_BOOTSTRAP_SERVERS)
            defaultTags()
          }
        }
        basicSpan(it, "producer callback", span(0))
        span {
          serviceName "kafka"
          operationName "kafka.produce"
          resourceName "Produce Topic $SHARED_TOPIC"
          spanType "queue"
          errored false
          childOf span(0)
          tags {
            "$Tags.COMPONENT" "java-kafka"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_PRODUCER
            if ({ isDataStreamsEnabled()}) {
              "$DDTags.PATHWAY_HASH" { String }
            }
            "$InstrumentationTags.KAFKA_BOOTSTRAP_SERVERS" producerProps.get(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG)
            peerServiceFrom(InstrumentationTags.KAFKA_BOOTSTRAP_SERVERS)
            defaultTags()
          }
        }
      }
      trace(1) {
        span {
          serviceName "kafka"
          operationName "kafka.consume"
          resourceName "Consume Topic $SHARED_TOPIC"
          spanType "queue"
          errored false
          tags {
            "$Tags.COMPONENT" "java-kafka"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CONSUMER
            "$InstrumentationTags.PARTITION" { it >= 0 }
            "$InstrumentationTags.OFFSET" 0
            "$InstrumentationTags.CONSUMER_GROUP" "sender"
            "$InstrumentationTags.KAFKA_BOOTSTRAP_SERVERS" consumerProperties.get(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG)
            "$InstrumentationTags.RECORD_QUEUE_TIME_MS" { it >= 0 }
            "$InstrumentationTags.RECORD_END_TO_END_DURATION_MS" { it >= 0 }
            if ({ isDataStreamsEnabled()}) {
              "$DDTags.PATHWAY_HASH" { String }
            }
            defaultTags(true)
          }
        }
      }
      trace(1) {
        span {
          serviceName "kafka"
          operationName "kafka.consume"
          resourceName "Consume Topic $SHARED_TOPIC"
          spanType "queue"
          errored false
          tags {
            "$Tags.COMPONENT" "java-kafka"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CONSUMER
            "$InstrumentationTags.PARTITION" { it >= 0 }
            "$InstrumentationTags.OFFSET" { it >= 0 && it < 2 }
            "$InstrumentationTags.CONSUMER_GROUP" "sender"
            "$InstrumentationTags.KAFKA_BOOTSTRAP_SERVERS" consumerProperties.get(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG)
            "$InstrumentationTags.RECORD_QUEUE_TIME_MS" { it >= 0 }
            "$InstrumentationTags.RECORD_END_TO_END_DURATION_MS" { it >= 0 }
            if ({ isDataStreamsEnabled()}) {
              "$DDTags.PATHWAY_HASH" { String }
            }
            defaultTags(true)
          }
        }
      }
      trace(1) {
        span {
          serviceName "kafka"
          operationName "kafka.consume"
          resourceName "Consume Topic $SHARED_TOPIC"
          spanType "queue"
          errored false
          tags {
            "$Tags.COMPONENT" "java-kafka"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CONSUMER
            "$InstrumentationTags.PARTITION" { it >= 0 }
            "$InstrumentationTags.OFFSET" { it >= 0 && it < 2 }
            "$InstrumentationTags.CONSUMER_GROUP" "sender"
            "$InstrumentationTags.KAFKA_BOOTSTRAP_SERVERS" consumerProperties.get(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG)
            "$InstrumentationTags.RECORD_QUEUE_TIME_MS" { it >= 0 }
            "$InstrumentationTags.RECORD_END_TO_END_DURATION_MS" { it >= 0 }
            if ({ isDataStreamsEnabled()}) {
              "$DDTags.PATHWAY_HASH" { String }
            }
            defaultTags(true)
          }
        }
      }
    }

    StatsGroup first = TEST_DATA_STREAMS_WRITER.groups.find { it.parentHash == 0 }
    verifyAll(first) {
      edgeTags == [
        "direction:out",
        "kafka_cluster_id:$clusterId",
        "topic:$SHARED_TOPIC".toString(),
        "type:kafka"
      ]
      edgeTags.size() == 4
      payloadSize.minValue > 0.0
    }

    StatsGroup second = TEST_DATA_STREAMS_WRITER.groups.find { it.parentHash == first.hash }
    verifyAll(second) {
      edgeTags == [
        "direction:in",
        "group:sender",
        "kafka_cluster_id:$clusterId",
        "topic:$SHARED_TOPIC".toString(),
        "type:kafka"
      ]
      edgeTags.size() == 5
      payloadSize.minValue > 0.0
    }

    cleanup:
    producerFactory.destroy()
    container?.stop()
  }

  @Unroll
  def "test kafka client header propagation manual config"() {
    setup:
    def producerProps = KafkaTestUtils.producerProps(embeddedKafka.getBrokersAsString())
    def producerFactory = new DefaultKafkaProducerFactory<String, String>(producerProps)
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
    producerFactory.destroy()
    container?.stop()

    where:
    value   | expected
    "false" | false
    "true"  | true
  }


  def containerProperties() {
    try {
      // Different class names for test and latestDepTest.
      return Class.forName("org.springframework.kafka.listener.config.ContainerProperties").newInstance(SHARED_TOPIC)
    } catch (ClassNotFoundException | NoClassDefFoundError e) {
      return Class.forName("org.springframework.kafka.listener.ContainerProperties").newInstance(SHARED_TOPIC)
    }
  }


  def waitForKafkaMetadataUpdate(KafkaTemplate kafkaTemplate) {
    kafkaTemplate.flush()
    Producer<String, String> wrappedProducer = kafkaTemplate.getTheProducer()
    assert(wrappedProducer instanceof DefaultKafkaProducerFactory.CloseSafeProducer)
    Producer<String, String> producer = wrappedProducer.delegate
    assert(producer instanceof KafkaProducer)
    String clusterId = producer.metadata.fetch().clusterResource().clusterId()
    while (clusterId == null || clusterId.isEmpty()) {
      Thread.sleep(1500)
      clusterId = producer.metadata.fetch().clusterResource().clusterId()
    }
    return clusterId
  }
}

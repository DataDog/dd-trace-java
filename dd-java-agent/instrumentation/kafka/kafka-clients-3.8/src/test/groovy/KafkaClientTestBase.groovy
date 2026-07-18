import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.agent.test.naming.VersionedNamingTestBase
import datadog.trace.api.Config
import datadog.trace.api.config.TraceInstrumentationConfig
import datadog.trace.api.config.TracerConfig
import datadog.trace.api.DDTags
import datadog.trace.api.datastreams.DataStreamsTags
import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.common.writer.ListWriter
import datadog.trace.core.DDSpan
import datadog.trace.core.datastreams.StatsGroup
import datadog.trace.instrumentation.kafka_common.ClusterIdHolder
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.Producer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.header.internals.RecordHeader
import org.apache.kafka.common.header.internals.RecordHeaders
import org.apache.kafka.common.serialization.StringSerializer

import java.nio.charset.StandardCharsets
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.listener.ContainerProperties
import org.springframework.kafka.listener.KafkaMessageListenerContainer
import org.springframework.kafka.listener.MessageListener
import org.springframework.kafka.test.EmbeddedKafkaBroker
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker
import org.springframework.kafka.test.utils.ContainerTestUtils
import org.springframework.kafka.test.utils.KafkaTestUtils
import spock.lang.IgnoreIf

import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

import static datadog.trace.agent.test.asserts.TagsAssert.codeOriginTags
import static datadog.trace.agent.test.utils.TraceUtils.basicSpan
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.isAsyncPropagationEnabled

abstract class KafkaClientTestBase extends VersionedNamingTestBase {
  static final SHARED_TOPIC = "shared.topic"

  EmbeddedKafkaBroker embeddedKafka

  def setup() {
    embeddedKafka = new EmbeddedKafkaKraftBroker(1, 2, SHARED_TOPIC)
    embeddedKafka.afterPropertiesSet()

    TEST_WRITER.setFilter(dropKafkaPoll)
  }

  def cleanup() {
    embeddedKafka.destroy()
  }

  @Override
  boolean useStrictTraceWrites() {
    // TODO fix this by making sure that spans get closed properly
    return false
  }

  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    codeOriginSetup()
    injectSysConfig("dd.kafka.e2e.duration.enabled", "true")
  }

  public static final LinkedHashMap<String, String> PRODUCER_PATHWAY_EDGE_TAGS

  // filter out Kafka poll, since the function is called in a loop, giving inconsistent results
  final ListWriter.Filter dropKafkaPoll = new ListWriter.Filter() {
    @Override
    boolean accept(List<DDSpan> trace) {
      return !(trace.size() == 1 &&
        trace.get(0).getResourceName().toString().equals("kafka.poll"))
    }
  }

  final ListWriter.Filter dropEmptyKafkaPoll = new ListWriter.Filter() {
    @Override
    boolean accept(List<DDSpan> trace) {
      return !(trace.size() == 1 &&
        trace.get(0).getResourceName().toString().equals("kafka.poll") &&
        trace.get(0).getTag(InstrumentationTags.KAFKA_RECORDS_COUNT).equals(0))
    }
  }

  // TraceID, start times & names changed based on the configuration, so overriding the sort to give consistent test results
  private static class SortKafkaTraces implements Comparator<List<DDSpan>> {
    @Override
    int compare(List<DDSpan> o1, List<DDSpan> o2) {
      return rootSpanTrace(o1) - rootSpanTrace(o2)
    }

    int rootSpanTrace(List<DDSpan> trace) {
      assert !trace.isEmpty()
      def rootSpan = trace.get(0).localRootSpan
      switch (rootSpan.operationName.toString()) {
        case "parent":
          return 3
        case "kafka.poll":
          return 2
        default:
          return 1
      }
    }
  }

  static {
    PRODUCER_PATHWAY_EDGE_TAGS = new LinkedHashMap<>(3)
    PRODUCER_PATHWAY_EDGE_TAGS.put("direction", "out")
    PRODUCER_PATHWAY_EDGE_TAGS.put("topic", SHARED_TOPIC)
    PRODUCER_PATHWAY_EDGE_TAGS.put("type", "kafka")
  }

  @Override
  int version() {
    0
  }

  @Override
  String operation() {
    return null
  }

  String operationForProducer() {
    "kafka.produce"
  }

  String operationForConsumer() {
    "kafka.consume"
  }

  String serviceForTimeInQueue() {
    "kafka"
  }

  // whether the consumer-scope-deferring flag is on for this variant, i.e. the last record's
  // consume span is deferred past the poll loop instead of closed immediately
  boolean deferConsumerScope() {
    false
  }

  abstract boolean hasQueueSpan()

  abstract boolean splitByDestination()

  @Override
  protected boolean isDataStreamsEnabled() {
    return true
  }

  def "test kafka produce and consume"() {
    setup:
    def producerProps = KafkaTestUtils.producerProps(embeddedKafka.getBrokersAsString())
    if (isDataStreamsEnabled()) {
      producerProps.put(ProducerConfig.METADATA_MAX_AGE_CONFIG, 1000)
    }
    // set a low max request size, so that we can crash it
    TEST_WRITER.setFilter(dropEmptyKafkaPoll)
    KafkaProducer<String, String> producer = new KafkaProducer<>(producerProps, new StringSerializer(), new StringSerializer())
    String clusterId = null
    while (clusterId == null || clusterId.isEmpty()) {
      Thread.sleep(1500)
      clusterId = producer.metadata.fetch().clusterResource().clusterId()
    }

    // set up the Kafka consumer properties
    def consumerProperties = KafkaTestUtils.consumerProps("sender", "false", embeddedKafka)

    // create a Kafka consumer factory
    def consumerFactory = new DefaultKafkaConsumerFactory<String, String>(consumerProperties)

    // set the topic that needs to be consumed
    def containerProperties = new ContainerProperties(SHARED_TOPIC)

    // create a Kafka MessageListenerContainer
    def container = new KafkaMessageListenerContainer<>(consumerFactory, containerProperties)

    // create a thread safe queue to store the received message
    def records = new LinkedBlockingQueue<ConsumerRecord<String, String>>()

    // setup a Kafka message listener
    container.setupMessageListener(new MessageListener<String, String>() {
        @Override
        void onMessage(ConsumerRecord<String, String> record) {
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
        assert isAsyncPropagationEnabled()
        if (ex == null) {
          runUnderTrace("producer callback") {}
        } else {
          runUnderTrace("producer exception: " + ex) {}
        }
      }
      blockUntilChildSpansFinished(2)
    }
    if (isDataStreamsEnabled()) {
      TEST_DATA_STREAMS_WRITER.waitForGroups(2)
      // wait for produce offset 0, commit offset 0 on partition 0 and 1, and commit offset 1 on 1 partition
      //TODO
      TEST_DATA_STREAMS_WRITER.waitForBacklogs(2)
    }

    then:
    //    // check that the message was received
    def received = records.poll(10, TimeUnit.SECONDS)
    received.value() == greeting
    received.key() == null

    // verify ClusterIdHolder was properly cleaned up after produce and consume
    ClusterIdHolder.get() == null
    int nTraces = isDataStreamsEnabled() ? 3 : 2
    int produceTraceIdx = nTraces - 1
    TEST_WRITER.waitForTraces(nTraces)
    def traces = new ArrayList<>(TEST_WRITER)
    traces.sort(new SortKafkaTraces())
    codeOriginTags(TEST_WRITER)
    assertTraces(nTraces, new SortKafkaTraces()) {
      if (hasQueueSpan()) {
        trace(2) {
          consumerSpan(it, consumerProperties, span(1))
          queueSpan(it, trace(produceTraceIdx)[2])
        }
      } else {
        trace(1) {
          consumerSpan(it, consumerProperties, trace(produceTraceIdx)[2])
        }
      }
      if (isDataStreamsEnabled()) {
        trace(1, { pollSpan(it) })
      }
      trace(3) {
        basicSpan(it, "parent")
        basicSpan(it, "producer callback", span(0))
        producerSpan(it, producerProps, span(0), false)
      }
    }
    def headers = received.headers()
    headers.iterator().hasNext()
    new String(headers.headers("x-datadog-trace-id").iterator().next().value()) == "${traces[produceTraceIdx][2].traceId}"
    new String(headers.headers("x-datadog-parent-id").iterator().next().value()) == "${traces[produceTraceIdx][2].spanId}"
    if (isDataStreamsEnabled()) {
      StatsGroup first = TEST_DATA_STREAMS_WRITER.groups.find { it.parentHash == 0 }
      verifyAll(first) {
        tags.hasAllTags("direction:out", "kafka_cluster_id:$clusterId", "topic:$SHARED_TOPIC".toString(), "type:kafka")
      }
      StatsGroup second = TEST_DATA_STREAMS_WRITER.groups.find { it.parentHash == first.hash }
      verifyAll(second) {
        tags.hasAllTags(
          "direction:in",
          "group:sender",
          "kafka_cluster_id:$clusterId",
          "topic:$SHARED_TOPIC".toString(),
          "type:kafka"
          )
      }

      def sorted = new ArrayList<DataStreamsTags>(TEST_DATA_STREAMS_WRITER.backlogs).sort { it.type }
      verifyAll(sorted) {
        size() == 2
        get(0).hasAllTags(
          "consumer_group:sender",
          "kafka_cluster_id:$clusterId",
          "partition:" + received.partition(),
          "topic:$SHARED_TOPIC",
          "type:kafka_commit"
          )
        get(1).hasAllTags(
          "kafka_cluster_id:$clusterId",
          "partition:" + received.partition(),
          "topic:" + SHARED_TOPIC,
          "type:kafka_produce"
          )
      }
    }

    cleanup:
    producer.close()
    container?.stop()
  }

  def "test producing message too large"() {
    setup:
    def producerProps = KafkaTestUtils.producerProps(embeddedKafka.getBrokersAsString())
    producerProps.put(ProducerConfig.MAX_REQUEST_SIZE_CONFIG, 10)

    KafkaProducer<String, String> producer = new KafkaProducer<>(producerProps, new StringSerializer(), new StringSerializer())

    when:
    String greeting = "Hello Spring Kafka"
    Future<RecordMetadata> future = producer.send(new ProducerRecord(SHARED_TOPIC, greeting)) { meta, ex ->
    }
    future.get()
    then:
    thrown ExecutionException
    cleanup:
    producer.close()
  }

  def "test spring kafka template produce and consume"() {
    setup:

    def senderProps = KafkaTestUtils.producerProps(embeddedKafka.getBrokersAsString())
    if (isDataStreamsEnabled()) {
      senderProps.put(ProducerConfig.METADATA_MAX_AGE_CONFIG, 1000)
    }
    def producerFactory = new DefaultKafkaProducerFactory<String, String>(senderProps)
    def kafkaTemplate = new KafkaTemplate<String, String>(producerFactory)
    String clusterId = null
    if (isDataStreamsEnabled()) {
      clusterId = waitForKafkaMetadataUpdate(kafkaTemplate)
    }

    // set up the Kafka consumer properties
    def consumerProperties = KafkaTestUtils.consumerProps(embeddedKafka.getBrokersAsString(), "sender", "false")

    // create a Kafka consumer factory
    def consumerFactory = new DefaultKafkaConsumerFactory<String, String>(consumerProperties)

    // set the topic that needs to be consumed
    def containerProperties = new ContainerProperties(SHARED_TOPIC)

    // create a Kafka MessageListenerContainer
    def container = new KafkaMessageListenerContainer<>(consumerFactory, containerProperties)

    // create a thread safe queue to store the received message
    def records = new LinkedBlockingQueue<ConsumerRecord<String, String>>()

    // setup a Kafka message listener
    container.setupMessageListener(new MessageListener<String, String>() {
        @Override
        void onMessage(ConsumerRecord<String, String> record) {
          records.add(record)
        }
      })

    // start the container and underlying message listener
    container.start()

    // wait until the container has the required number of assigned partitions
    ContainerTestUtils.waitForAssignment(container, container.assignedPartitions.size())

    when:
    String greeting = "Hello Spring Kafka Sender!"
    runUnderTrace("parent") {
      kafkaTemplate.send(SHARED_TOPIC, greeting).whenComplete { meta, ex ->
        assert isAsyncPropagationEnabled()
        if (ex == null) {
          runUnderTrace("producer callback") {}
        } else {
          runUnderTrace("producer exception: " + ex) {}
        }
      }
      blockUntilChildSpansFinished(2)
    }
    if (isDataStreamsEnabled()) {
      TEST_DATA_STREAMS_WRITER.waitForGroups(2)
      // wait for produce offset 0, commit offset 0 on partition 0 and 1, and commit offset 1 on 1 partition.
      //TODO
      TEST_DATA_STREAMS_WRITER.waitForBacklogs(2)
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
        producerSpan(it, senderProps, span(0), false)
      }
      if (hasQueueSpan()) {
        trace(2) {
          consumerSpan(it, consumerProperties, trace(1)[1])
          queueSpan(it, trace(0)[2])
        }
      } else {
        trace(1) {
          consumerSpan(it, consumerProperties, trace(0)[2])
        }
      }
    }

    // sort a snapshot so the producer trace is deterministically first, regardless of write order
    def sortedTraces = new ArrayList<>(TEST_WRITER)
    sortedTraces.sort(SORT_TRACES_BY_ID)
    def headers = received.headers()
    headers.iterator().hasNext()
    new String(headers.headers("x-datadog-trace-id").iterator().next().value()) == "${sortedTraces[0][2].traceId}"
    new String(headers.headers("x-datadog-parent-id").iterator().next().value()) == "${sortedTraces[0][2].spanId}"

    if (isDataStreamsEnabled()) {
      StatsGroup first = TEST_DATA_STREAMS_WRITER.groups.find { it.parentHash == 0 }
      verifyAll(first) {
        tags.hasAllTags(
          "direction:out",
          "kafka_cluster_id:$clusterId".toString(),
          "topic:$SHARED_TOPIC".toString(),
          "type:kafka"
          )
      }

      StatsGroup second = TEST_DATA_STREAMS_WRITER.groups.find { it.parentHash == first.hash }
      verifyAll(second) {
        tags.hasAllTags(
          "direction:in",
          "group:sender",
          "kafka_cluster_id:$clusterId".toString(),
          "topic:$SHARED_TOPIC".toString(),
          "type:kafka"
          )
      }
      def items = new ArrayList<DataStreamsTags>(TEST_DATA_STREAMS_WRITER.backlogs).sort { it.type }
      verifyAll(items) {
        size() == 2
        get(0).hasAllTags(
          "consumer_group:sender",
          "kafka_cluster_id:$clusterId".toString(),
          "partition:" + received.partition(),
          "topic:" + SHARED_TOPIC,
          "type:kafka_commit"
          )
        get(1).hasAllTags(
          "kafka_cluster_id:$clusterId".toString(),
          "partition:" + received.partition(),
          "topic:" + SHARED_TOPIC,
          "type:kafka_produce"
          )
      }
    }

    cleanup:
    producerFactory.stop()
    container?.stop()
  }

  def "test pass through tombstone"() {
    setup:

    def senderProps = KafkaTestUtils.producerProps(embeddedKafka.getBrokersAsString())
    def producerFactory = new DefaultKafkaProducerFactory<String, String>(senderProps)
    def kafkaTemplate = new KafkaTemplate<String, String>(producerFactory)

    // set up the Kafka consumer properties
    def consumerProperties = KafkaTestUtils.consumerProps(embeddedKafka.getBrokersAsString(), "sender", "false")

    // create a Kafka consumer factory
    def consumerFactory = new DefaultKafkaConsumerFactory<String, String>(consumerProperties)

    // set the topic that needs to be consumed
    def containerProperties = new ContainerProperties(SHARED_TOPIC)

    // create a Kafka MessageListenerContainer
    def container = new KafkaMessageListenerContainer<>(consumerFactory, containerProperties)

    // create a thread safe queue to store the received message
    def records = new LinkedBlockingQueue<ConsumerRecord<String, String>>()

    // setup a Kafka message listener
    container.setupMessageListener(new MessageListener<String, String>() {
        @Override
        void onMessage(ConsumerRecord<String, String> record) {
          records.add(record)
        }
      })

    // start the container and underlying message listener
    container.start()

    // wait until the container has the required number of assigned partitions
    ContainerTestUtils.waitForAssignment(container, container.assignedPartitions.size())

    when:
    kafkaTemplate.send(SHARED_TOPIC, null)

    then:
    // check that the message was received
    def received = records.poll(5, TimeUnit.SECONDS)
    received.value() == null
    received.key() == null

    assertTraces(2, SORT_TRACES_BY_ID) {
      trace(1) {
        producerSpan(it, senderProps, null, false, true)
      }
      if (hasQueueSpan()) {
        trace(2) {
          consumerSpan(it, consumerProperties, trace(1)[1], 0..0, true)
          queueSpan(it, trace(0)[0])
        }
      } else {
        trace(1) {
          consumerSpan(it, consumerProperties, trace(0)[0], 0..0, true)
        }
      }
    }

    cleanup:
    producerFactory.stop()
    container?.stop()
  }

  // when the scope is deferred, the single (and therefore last) record's consume span is left
  // active past the loop instead of flushing immediately, so this test's immediate-flush
  // assumption does not hold; the dedicated deferred-close cases cover the ON behavior
  @IgnoreIf({ instance.deferConsumerScope() })
  def "test records(TopicPartition) kafka consume"() {
    setup:
    // set up the Kafka consumer properties
    def kafkaPartition = 0
    def consumerProperties = KafkaTestUtils.consumerProps(embeddedKafka.getBrokersAsString(), "sender", "false")
    consumerProperties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
    def consumer = new KafkaConsumer<String, String>(consumerProperties)

    def senderProps = KafkaTestUtils.producerProps(embeddedKafka.getBrokersAsString())
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
    !recs.hasNext()
    first.value() == greeting
    first.key() == null

    assertTraces(2, SORT_TRACES_BY_ID) {
      trace(1) {
        producerSpan(it, senderProps)
      }
      if (hasQueueSpan()) {
        trace(2) {
          consumerSpan(it, consumerProperties, trace(1)[1])
          queueSpan(it, trace(0)[0])
        }
      } else {
        trace(1) {
          consumerSpan(it, consumerProperties, trace(0)[0])
        }
      }
    }

    cleanup:
    consumer.close()
    producer.close()
  }

  // see the deferred-scope note on "test records(TopicPartition) kafka consume" above
  @IgnoreIf({ instance.deferConsumerScope() })
  def "test records(TopicPartition).subList kafka consume"() {
    setup:

    def senderProps = KafkaTestUtils.producerProps(embeddedKafka.getBrokersAsString())
    def consumerProperties = KafkaTestUtils.consumerProps(embeddedKafka.getBrokersAsString(), "sender", "false")

    // set up the Kafka consumer properties
    def kafkaPartition = 0
    consumerProperties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
    def consumer = new KafkaConsumer<String, String>(consumerProperties)

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
    !recs.hasNext()
    first.value() == greeting
    first.key() == null

    assertTraces(2, SORT_TRACES_BY_ID) {
      trace(1) {
        producerSpan(it, senderProps)
      }
      if (hasQueueSpan()) {
        trace(2) {
          consumerSpan(it, consumerProperties, trace(1)[1])
          queueSpan(it, trace(0)[0])
        }
      } else {
        trace(1) {
          consumerSpan(it, consumerProperties, trace(0)[0])
        }
      }
    }

    cleanup:
    consumer.close()
    producer.close()
  }

  // see the deferred-scope note on "test records(TopicPartition) kafka consume" above
  @IgnoreIf({ instance.deferConsumerScope() })
  def "test records(TopicPartition).forEach kafka consume"() {
    setup:

    def senderProps = KafkaTestUtils.producerProps(embeddedKafka.getBrokersAsString())
    def consumerProperties = KafkaTestUtils.consumerProps(embeddedKafka.getBrokersAsString(), "sender", "false")

    // set up the Kafka consumer properties
    def kafkaPartition = 0
    consumerProperties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
    def consumer = new KafkaConsumer<String, String>(consumerProperties)

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
        producerSpan(it, senderProps)
      }
      if (hasQueueSpan()) {
        trace(2) {
          consumerSpan(it, consumerProperties, trace(1)[1])
          queueSpan(it, trace(0)[0])
        }
      } else {
        trace(1) {
          consumerSpan(it, consumerProperties, trace(0)[0])
        }
      }
    }

    cleanup:
    consumer.close()
    producer.close()
  }

  // the final forward and final backward record are each "last" for their direction, so both leave
  // a deferred, still-active consume span behind when the flag is on
  @IgnoreIf({ instance.deferConsumerScope() })
  def "test iteration backwards over ConsumerRecords"() {
    setup:

    def senderProps = KafkaTestUtils.producerProps(embeddedKafka.getBrokersAsString())
    def consumerProperties = KafkaTestUtils.consumerProps(embeddedKafka.getBrokersAsString(), "sender", "false")

    def kafkaPartition = 0
    consumerProperties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
    def consumer = new KafkaConsumer<String, String>(consumerProperties)

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
        producerSpan(it, senderProps)
      }
      trace(1) {
        producerSpan(it, senderProps)
      }
      trace(1) {
        producerSpan(it, senderProps)
      }

      // iterating to the end of ListIterator:
      if (hasQueueSpan()) {
        trace(2) {
          consumerSpan(it, consumerProperties, trace(3)[1], 0..0)
          queueSpan(it, trace(0)[0])
        }
        trace(2) {
          consumerSpan(it, consumerProperties, trace(4)[1], 1..1)
          queueSpan(it, trace(1)[0])
        }
        trace(2) {
          consumerSpan(it, consumerProperties, trace(5)[1], 2..2)
          queueSpan(it, trace(2)[0])
        }
      } else {
        trace(1) {
          consumerSpan(it, consumerProperties, trace(0)[0], 0..0)
        }
        trace(1) {
          consumerSpan(it, consumerProperties, trace(1)[0], 1..1)
        }
        trace(1) {
          consumerSpan(it, consumerProperties, trace(2)[0], 2..2)
        }
      }

      // backwards iteration over ListIterator to the beginning
      if (hasQueueSpan()) {
        trace(2) {
          consumerSpan(it, consumerProperties, trace(6)[1], 2..2)
          queueSpan(it, trace(2)[0])
        }
        trace(2) {
          consumerSpan(it, consumerProperties, trace(7)[1], 1..1)
          queueSpan(it, trace(1)[0])
        }
        trace(2) {
          consumerSpan(it, consumerProperties, trace(8)[1], 0..0)
          queueSpan(it, trace(0)[0])
        }
      } else {
        trace(1) {
          consumerSpan(it, consumerProperties, trace(2)[0], 2..2)
        }
        trace(1) {
          consumerSpan(it, consumerProperties, trace(1)[0], 1..1)
        }
        trace(1) {
          consumerSpan(it, consumerProperties, trace(0)[0], 0..0)
        }
      }
    }

    cleanup:
    consumer.close()
    producer.close()
  }

  def "test kafka client header propagation manual config"() {
    setup:

    def senderProps = KafkaTestUtils.producerProps(embeddedKafka.getBrokersAsString())
    def consumerProperties = KafkaTestUtils.consumerProps(embeddedKafka.getBrokersAsString(), "sender", "false")

    def producerFactory = new DefaultKafkaProducerFactory<String, String>(senderProps)
    def kafkaTemplate = new KafkaTemplate<String, String>(producerFactory)

    // create a Kafka consumer factory
    def consumerFactory = new DefaultKafkaConsumerFactory<String, String>(consumerProperties)

    // set the topic that needs to be consumed
    def containerProperties = new ContainerProperties(SHARED_TOPIC)

    // create a Kafka MessageListenerContainer
    def container = new KafkaMessageListenerContainer<>(consumerFactory, containerProperties)

    // create a thread safe queue to store the received message
    def records = new LinkedBlockingQueue<ConsumerRecord<String, String>>()

    // setup a Kafka message listener
    container.setupMessageListener(new MessageListener<String, String>() {
        @Override
        void onMessage(ConsumerRecord<String, String> record) {
          records.add(record)
          if (isDataStreamsEnabled()) {
            // even if header propagation is disabled, we want data streams to work.
            TEST_DATA_STREAMS_WRITER.waitForGroups(2)
          }
        }
      })

    // start the container and underlying message listener
    container.start()

    // wait until the container has the required number of assigned partitions
    ContainerTestUtils.waitForAssignment(container, container.assignedPartitions.size())

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
    value   | expected
    "false" | false
    "true"  | true
  }

  def "test producer extracts and uses existing trace context from record headers"() {
    setup:
    def senderProps = KafkaTestUtils.producerProps(embeddedKafka.getBrokersAsString())
    def producer = new KafkaProducer<>(senderProps)

    def existingTraceId = 1234567890123456L
    def existingSpanId = 9876543210987654L
    def headers = new RecordHeaders()
    headers.add(new RecordHeader("x-datadog-trace-id",
      String.valueOf(existingTraceId).getBytes(StandardCharsets.UTF_8)))
    headers.add(new RecordHeader("x-datadog-parent-id",
      String.valueOf(existingSpanId).getBytes(StandardCharsets.UTF_8)))

    when:
    def record = new ProducerRecord(SHARED_TOPIC, 0, null, "test-context-extraction", headers)
    producer.send(record).get()

    then:
    TEST_WRITER.waitForTraces(1)
    def producedSpan = TEST_WRITER[0][0]
    // Verify the span used the extracted context as parent
    producedSpan.traceId.toLong() == existingTraceId
    producedSpan.parentId == existingSpanId
    // Verify a new span was created (not reusing the extracted span ID)
    producedSpan.spanId != existingSpanId

    cleanup:
    producer?.close()
  }

  // when a consumer buffers records and writes to the database *after* the per-record iterator
  // loop, the write span is disconnected from the consume span when deferConsumerScope() is off,
  // and reparented under it when a flag-ON forked variant overrides deferConsumerScope() to true
  def "test work done after the consume loop is disconnected from the consume span"() {
    setup:
    def kafkaPartition = 0
    def consumerProperties = KafkaTestUtils.consumerProps(embeddedKafka.getBrokersAsString(), "sender", "false")
    consumerProperties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
    def consumer = new KafkaConsumer<String, String>(consumerProperties)

    def senderProps = KafkaTestUtils.producerProps(embeddedKafka.getBrokersAsString())
    def producer = new KafkaProducer(senderProps)

    consumer.assign(Arrays.asList(new TopicPartition(SHARED_TOPIC, kafkaPartition)))

    when:
    def greeting = "Hello from MockConsumer!"
    producer.send(new ProducerRecord<Integer, String>(SHARED_TOPIC, kafkaPartition, null, greeting))

    then:
    TEST_WRITER.waitForTraces(1)
    def pollResult = KafkaTestUtils.getRecords(consumer)

    // No DB work inside the iterator loop -- payloads are only buffered here.
    def buffer = []
    for (record in pollResult) {
      buffer.add(record.value())
    }
    // "db.write" stands in for the application's database write, run after the consume loop.
    buffer.each {
      runUnderTrace("db.write") {}
    }
    // When deferConsumerScope() is on, the last record's consume span is intentionally left
    // open past the loop; closing the consumer is one of the triggers that finishes it (see the
    // dedicated close()/unsubscribe()/next-poll cases below) so the trace can flush.
    // When off, the consume span already finished during the loop and this is a no-op for it.
    consumer.close()

    then:
    // the produced record was actually consumed (fail clearly here instead of timing out below)
    buffer.size() == 1
    // Off: producer trace + consume trace (+ queue span, same trace) + the db.write root trace,
    // written out as 3 separate batches since db.write is disconnected (THE BUG).
    // On: producer trace + (consume trace + db.write), the latter now written out as a single
    // batch because db.write is nested under -- and flushes together with -- the consume span.
    TEST_WRITER.waitForTraces(deferConsumerScope() ? 2 : 3)
    List<DDSpan> spans = TEST_WRITER.flatten()
    def consumeSpan = spans.find {
      it.operationName.toString() == operationForConsumer()
    }
    def dbWriteSpan = spans.find {
      it.operationName.toString() == "db.write"
    }

    consumeSpan != null
    dbWriteSpan != null

    if (deferConsumerScope()) {
      // with the deferred-close flag on, the post-loop db.write nests under the last record's
      // still-active consume span (assert required: conditions nested in if/else are not treated
      // as implicit Spock conditions)
      assert dbWriteSpan.traceId == consumeSpan.traceId
      assert dbWriteSpan.parentId == consumeSpan.spanId
    } else {
      // bug: the DB write is its own root trace, not a child of the consume span, and the consume
      // span is left childless
      assert dbWriteSpan.parentId == 0
      assert dbWriteSpan.traceId != consumeSpan.traceId
      assert spans.every {
        it.parentId != consumeSpan.spanId
      }
    }

    cleanup:
    consumer.close()
    producer.close()
  }

  // The following cases only apply when the deferred-close flag is on; the flag-off behavior
  // above (and everywhere else in this suite) is unaffected.

  @IgnoreIf({ !instance.deferConsumerScope() })
  def "test only the last of multiple records keeps its consume span active past the loop"() {
    setup:
    def kafkaPartition = 0
    def consumerProperties = KafkaTestUtils.consumerProps(embeddedKafka.getBrokersAsString(), "sender", "false")
    consumerProperties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
    def consumer = new KafkaConsumer<String, String>(consumerProperties)

    def senderProps = KafkaTestUtils.producerProps(embeddedKafka.getBrokersAsString())
    def producer = new KafkaProducer(senderProps)

    consumer.assign(Arrays.asList(new TopicPartition(SHARED_TOPIC, kafkaPartition)))

    when:
    def greetings = ["msg 1", "msg 2", "msg 3"]
    greetings.each {
      producer.send(new ProducerRecord<Integer, String>(SHARED_TOPIC, kafkaPartition, null, it))
    }

    then:
    TEST_WRITER.waitForTraces(3)
    def pollResult = KafkaTestUtils.getRecords(consumer)
    def records = pollResult.records(new TopicPartition(SHARED_TOPIC, kafkaPartition))
    records.size() == 3
    def lastRecord = records.get(records.size() - 1)

    for (record in pollResult) {
      // no-op -- the loop itself drives TracingIterator#hasNext()/startNewRecordSpan()
    }
    def lastSpan = activeSpan()

    // only the LAST record's consume span survives the loop; earlier records still close in-loop.
    lastSpan != null
    lastSpan.operationName.toString() == operationForConsumer()
    (lastSpan.getTag(InstrumentationTags.OFFSET) as long) == lastRecord.offset()

    consumer.close()

    then:
    // 3 producer traces + 3 consume traces (a queue span, if any, shares the consume trace)
    TEST_WRITER.waitForTraces(6)

    cleanup:
    consumer.close()
    producer.close()
  }

  @IgnoreIf({ !instance.deferConsumerScope() })
  def "test a lingering consume span is closed by the next poll, not leaked onto it"() {
    setup:
    def kafkaPartition = 0
    def consumerProperties = KafkaTestUtils.consumerProps(embeddedKafka.getBrokersAsString(), "sender", "false")
    consumerProperties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
    def consumer = new KafkaConsumer<String, String>(consumerProperties)

    def senderProps = KafkaTestUtils.producerProps(embeddedKafka.getBrokersAsString())
    def producer = new KafkaProducer(senderProps)

    consumer.assign(Arrays.asList(new TopicPartition(SHARED_TOPIC, kafkaPartition)))

    when:
    producer.send(new ProducerRecord<Integer, String>(SHARED_TOPIC, kafkaPartition, null, "msg 1"))

    then:
    TEST_WRITER.waitForTraces(1)
    def pollResult = KafkaTestUtils.getRecords(consumer)
    def receivedRecords = 0
    for (record in pollResult) {
      // no-op -- driving the real (instrumented) iterator is what creates/activates the
      // consume span; ConsumerRecords#count() reads an internal map size and never touches
      // the wrapped iterator, so it must not be used here.
      receivedRecords++
    }
    receivedRecords == 1
    activeSpan() != null
    activeSpan().operationName.toString() == operationForConsumer()

    // a second, empty poll is itself a close trigger for the span left lingering by the first
    // poll -- see RecordsAdvice#onEnter, which closes it before this poll's own work starts.
    def secondPoll = consumer.poll(java.time.Duration.ofMillis(500))
    secondPoll.count() == 0

    then:
    // the first record's consume trace (producer trace already counted) has now flushed.
    TEST_WRITER.waitForTraces(2)
    List<DDSpan> spans = TEST_WRITER.flatten()
    def consumeSpan = spans.find {
      it.operationName.toString() == operationForConsumer()
    }
    consumeSpan != null
    // the second poll's span (if DSM created one at all) must not be nested under the stale
    // consume span from the first poll.
    spans.every {
      it.resourceName.toString() != "kafka.poll" || it.parentId != consumeSpan.spanId
    }

    cleanup:
    consumer.close()
    producer.close()
  }

  @IgnoreIf({ !instance.deferConsumerScope() })
  def "test close() finishes a lingering consume span"() {
    setup:
    def kafkaPartition = 0
    def consumerProperties = KafkaTestUtils.consumerProps(embeddedKafka.getBrokersAsString(), "sender", "false")
    consumerProperties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
    def consumer = new KafkaConsumer<String, String>(consumerProperties)

    def senderProps = KafkaTestUtils.producerProps(embeddedKafka.getBrokersAsString())
    def producer = new KafkaProducer(senderProps)

    consumer.assign(Arrays.asList(new TopicPartition(SHARED_TOPIC, kafkaPartition)))

    when:
    producer.send(new ProducerRecord<Integer, String>(SHARED_TOPIC, kafkaPartition, null, "msg 1"))

    then:
    TEST_WRITER.waitForTraces(1)
    def pollResult = KafkaTestUtils.getRecords(consumer)
    def receivedRecords = 0
    for (record in pollResult) {
      // no-op -- driving the real (instrumented) iterator is what creates/activates the
      // consume span; ConsumerRecords#count() reads an internal map size and never touches
      // the wrapped iterator, so it must not be used here.
      receivedRecords++
    }
    receivedRecords == 1
    activeSpan() != null
    activeSpan().operationName.toString() == operationForConsumer()

    consumer.close()

    then:
    TEST_WRITER.waitForTraces(2)

    cleanup:
    producer.close()
  }

  @IgnoreIf({ !instance.deferConsumerScope() })
  def "test a consume span deferred on a worker thread is finished by a close on another thread"() {
    setup:
    def kafkaPartition = 0
    def consumerProperties = KafkaTestUtils.consumerProps(embeddedKafka.getBrokersAsString(), "sender", "false")
    consumerProperties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
    def consumer = new KafkaConsumer<String, String>(consumerProperties)

    def senderProps = KafkaTestUtils.producerProps(embeddedKafka.getBrokersAsString())
    def producer = new KafkaProducer(senderProps)

    consumer.assign(Arrays.asList(new TopicPartition(SHARED_TOPIC, kafkaPartition)))

    when:
    producer.send(new ProducerRecord<Integer, String>(SHARED_TOPIC, kafkaPartition, null, "msg 1"))

    then:
    TEST_WRITER.waitForTraces(1)
    def pollResult = KafkaTestUtils.getRecords(consumer)

    // iterate on a worker thread so the deferred consume span is left active there, never
    // top-of-stack on the main thread that closes the consumer
    def received = new java.util.concurrent.atomic.AtomicInteger(0)
    DDSpan lingering = null
    Thread.start {
      for (record in pollResult) {
        // no-op -- driving the real (instrumented) iterator creates the consume span here
        received.incrementAndGet()
      }
      lingering = (DDSpan) activeSpan()
    }.join()

    received.get() == 1
    lingering != null
    lingering.operationName.toString() == operationForConsumer()

    consumer.close()

    then:
    // only the per-consumer owner-aware handle can reach the worker thread's span from here
    TEST_WRITER.waitForTraces(2)

    cleanup:
    producer.close()
  }

  @IgnoreIf({ !instance.deferConsumerScope() })
  def "test unsubscribe() finishes a lingering consume span"() {
    setup:
    def kafkaPartition = 0
    def consumerProperties = KafkaTestUtils.consumerProps(embeddedKafka.getBrokersAsString(), "sender", "false")
    consumerProperties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
    def consumer = new KafkaConsumer<String, String>(consumerProperties)

    def senderProps = KafkaTestUtils.producerProps(embeddedKafka.getBrokersAsString())
    def producer = new KafkaProducer(senderProps)

    consumer.assign(Arrays.asList(new TopicPartition(SHARED_TOPIC, kafkaPartition)))

    when:
    producer.send(new ProducerRecord<Integer, String>(SHARED_TOPIC, kafkaPartition, null, "msg 1"))

    then:
    TEST_WRITER.waitForTraces(1)
    def pollResult = KafkaTestUtils.getRecords(consumer)
    def receivedRecords = 0
    for (record in pollResult) {
      // no-op -- driving the real (instrumented) iterator is what creates/activates the
      // consume span; ConsumerRecords#count() reads an internal map size and never touches
      // the wrapped iterator, so it must not be used here.
      receivedRecords++
    }
    receivedRecords == 1
    activeSpan() != null
    activeSpan().operationName.toString() == operationForConsumer()

    consumer.unsubscribe()

    then:
    TEST_WRITER.waitForTraces(2)

    cleanup:
    consumer.close()
    producer.close()
  }

  @IgnoreIf({ !instance.deferConsumerScope() })
  def "test a lingering consume span is eventually finished with no further trigger"() {
    setup:
    def kafkaPartition = 0
    def consumerProperties = KafkaTestUtils.consumerProps(embeddedKafka.getBrokersAsString(), "sender", "false")
    consumerProperties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
    def consumer = new KafkaConsumer<String, String>(consumerProperties)

    def senderProps = KafkaTestUtils.producerProps(embeddedKafka.getBrokersAsString())
    def producer = new KafkaProducer(senderProps)

    consumer.assign(Arrays.asList(new TopicPartition(SHARED_TOPIC, kafkaPartition)))

    when:
    producer.send(new ProducerRecord<Integer, String>(SHARED_TOPIC, kafkaPartition, null, "msg 1"))

    then:
    TEST_WRITER.waitForTraces(1)
    def pollResult = KafkaTestUtils.getRecords(consumer)
    def receivedRecords = 0
    for (record in pollResult) {
      // no-op -- driving the real (instrumented) iterator is what creates/activates the
      // consume span; ConsumerRecords#count() reads an internal map size and never touches
      // the wrapped iterator, so it must not be used here.
      receivedRecords++
    }
    receivedRecords == 1
    activeSpan() != null
    activeSpan().operationName.toString() == operationForConsumer()

    // no further poll, no close()/unsubscribe(), no commit -- the lingering ITERATION-sourced
    // consume span is reaped by the scope manager's native root-iteration-scope keep-alive
    // (RootIterationScopeCleaner), so it must eventually flush without any explicit trigger.
    TEST_WRITER.waitForTraces(2)

    cleanup:
    consumer.close()
    producer.close()
  }

  @IgnoreIf({ !instance.deferConsumerScope() })
  def "test commitSync() does not finish a lingering consume span"() {
    setup:
    def kafkaPartition = 0
    def consumerProperties = KafkaTestUtils.consumerProps(embeddedKafka.getBrokersAsString(), "sender", "false")
    consumerProperties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
    def consumer = new KafkaConsumer<String, String>(consumerProperties)

    def senderProps = KafkaTestUtils.producerProps(embeddedKafka.getBrokersAsString())
    def producer = new KafkaProducer(senderProps)

    consumer.assign(Arrays.asList(new TopicPartition(SHARED_TOPIC, kafkaPartition)))

    when:
    producer.send(new ProducerRecord<Integer, String>(SHARED_TOPIC, kafkaPartition, null, "msg 1"))

    then:
    TEST_WRITER.waitForTraces(1)
    def pollResult = KafkaTestUtils.getRecords(consumer)
    def receivedRecords = 0
    for (record in pollResult) {
      // no-op -- driving the real (instrumented) iterator is what creates/activates the
      // consume span; ConsumerRecords#count() reads an internal map size and never touches
      // the wrapped iterator, so it must not be used here.
      receivedRecords++
    }
    receivedRecords == 1
    def lingeringSpan = activeSpan()
    lingeringSpan != null
    lingeringSpan.operationName.toString() == operationForConsumer()

    consumer.commitSync()

    then:
    // commit is explicitly NOT a close trigger: the same span must still be active
    // and unfinished immediately after the commit call. This is checked synchronously (rather
    // than via a bounded waitForTraces window) because the tracer's generic root-iteration-scope
    // keep-alive (dd.trace.scope.iteration.keep-alive, defaulted to 1s in tests) independently
    // reaps this same lingering ITERATION-sourced scope around the same time -- a timing-based
    // "did it flush yet" check would be racing that unrelated cleanup, not testing commit.
    activeSpan() == lingeringSpan
    !(lingeringSpan as DDSpan).isFinished()

    cleanup:
    consumer.close()
    producer.close()
  }

  def producerSpan(
    TraceAssert trace,
    Map<String, ?> config,
    DDSpan parentSpan = null,
    boolean partitioned = true,
    boolean tombstone = false,
    String schema = null
  ) {
    trace.span {
      serviceName service()
      operationName operationForProducer()
      resourceName "Produce Topic $SHARED_TOPIC"
      spanType "queue"
      errored false
      measured true
      if (parentSpan) {
        childOf parentSpan
      } else {
        parent()
      }
      final boolean isV0 = version() == 0
      tags {
        "$Tags.COMPONENT" "java-kafka"
        "$Tags.SPAN_KIND" Tags.SPAN_KIND_PRODUCER
        "$InstrumentationTags.KAFKA_BOOTSTRAP_SERVERS" config.get(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG)
        "$InstrumentationTags.MESSAGING_DESTINATION_NAME" "$SHARED_TOPIC"
        "$InstrumentationTags.PARTITION" { it >= 0 }
        "$InstrumentationTags.OFFSET" { it >= 0 }
        "$InstrumentationTags.KAFKA_CLUSTER_ID" { String }
        if (tombstone) {
          "$InstrumentationTags.TOMBSTONE" true
        }
        if ({ isDataStreamsEnabled() }) {
          "$DDTags.PATHWAY_HASH" { String }
          if (schema != null) {
            "$DDTags.SCHEMA_DEFINITION" schema
            "$DDTags.SCHEMA_WEIGHT" 1
            "$DDTags.SCHEMA_TYPE" "avro"
            "$DDTags.SCHEMA_OPERATION" "serialization"
            "$DDTags.SCHEMA_ID" "10810872322569724838"
          }
        }
        peerServiceFrom(InstrumentationTags.KAFKA_BOOTSTRAP_SERVERS)
        if (isV0) {
          // in v0 the service name is always set to DD_SERVICE while it should just be unset as v1
          // this is a buggy behaviour that could not be easily fixed.
          serviceNameSource "java-kafka"
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
      serviceName splitByDestination() ? "$SHARED_TOPIC" : serviceForTimeInQueue()
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
    Map<String, Object> config,
    DDSpan parentSpan = null,
    Range offset = 0..0,
    boolean tombstone = false,
    boolean distributedRootSpan = !hasQueueSpan()
  ) {
    trace.span {
      serviceName service()
      operationName operationForConsumer()
      resourceName "Consume Topic $SHARED_TOPIC"
      spanType "queue"
      errored false
      measured true
      if (parentSpan) {
        childOf parentSpan
      } else {
        parent()
      }
      final boolean isV0 = version() == 0
      tags {
        "$Tags.COMPONENT" "java-kafka"
        "$Tags.SPAN_KIND" Tags.SPAN_KIND_CONSUMER
        "$InstrumentationTags.PARTITION" { it >= 0 }
        "$InstrumentationTags.OFFSET" { offset.containsWithinBounds(it as int) }
        "$InstrumentationTags.CONSUMER_GROUP" "sender"
        "$InstrumentationTags.KAFKA_BOOTSTRAP_SERVERS" config.get(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG)
        "$InstrumentationTags.KAFKA_CLUSTER_ID" { String }
        "$InstrumentationTags.RECORD_QUEUE_TIME_MS" { it >= 0 }
        "$InstrumentationTags.RECORD_END_TO_END_DURATION_MS" { it >= 0 }
        "$InstrumentationTags.MESSAGING_DESTINATION_NAME" "$SHARED_TOPIC"
        if (tombstone) {
          "$InstrumentationTags.TOMBSTONE" true
        }
        if ({ isDataStreamsEnabled() }) {
          "$DDTags.PATHWAY_HASH" { String }
        }
        if (isV0) {
          serviceNameSource "java-kafka"
        }
        defaultTags(distributedRootSpan)
      }
    }
  }

  def pollSpan(
    TraceAssert trace,
    int recordCount = 1,
    DDSpan parentSpan = null,
    Range offset = 0..0,
    boolean tombstone = false,
    boolean distributedRootSpan = !hasQueueSpan()
  ) {
    trace.span {
      serviceName Config.get().getServiceName()
      operationName "kafka.poll"
      resourceName "kafka.poll"
      errored false
      measured false
      tags {
        "$InstrumentationTags.KAFKA_RECORDS_COUNT" recordCount
        defaultTags(true)
      }
    }
  }

  def waitForKafkaMetadataUpdate(KafkaTemplate kafkaTemplate) {
    kafkaTemplate.flush()
    Producer<String, String> wrappedProducer = kafkaTemplate.getTheProducer()
    assert (wrappedProducer instanceof DefaultKafkaProducerFactory.CloseSafeProducer)
    Producer<String, String> producer = wrappedProducer.delegate
    assert (producer instanceof KafkaProducer)
    String clusterId = producer.metadata.fetch().clusterResource().clusterId()
    while (clusterId == null || clusterId.isEmpty()) {
      Thread.sleep(1500)
      clusterId = producer.metadata.fetch().clusterResource().clusterId()
    }
    return clusterId
  }
}

abstract class KafkaClientForkedTest extends KafkaClientTestBase {
  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig("dd.kafka.legacy.tracing.enabled", "false")
    injectSysConfig("dd.service", "KafkaClientTest")
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

class KafkaClientV0ForkedTest extends KafkaClientForkedTest {
  @Override
  String service() {
    return "KafkaClientTest"
  }
}

class KafkaClientV1ForkedTest extends KafkaClientForkedTest {
  @Override
  int version() {
    1
  }

  @Override
  String service() {
    return "KafkaClientTest"
  }

  @Override
  String operationForProducer() {
    "kafka.send"
  }

  @Override
  String operationForConsumer() {
    return "kafka.process"
  }

  @Override
  String serviceForTimeInQueue() {
    "kafka-queue"
  }

  @Override
  boolean hasQueueSpan() {
    false
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
  String service() {
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

abstract class KafkaClientLegacyTracingForkedTest extends KafkaClientTestBase {
  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig("dd.service", "KafkaClientLegacyTest")
    injectSysConfig("dd.kafka.legacy.tracing.enabled", "true")
  }

  @Override
  String service() {
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

class KafkaClientLegacyTracingV0ForkedTest extends KafkaClientLegacyTracingForkedTest {
}

class KafkaClientLegacyTracingV1ForkedTest extends KafkaClientLegacyTracingForkedTest {

  @Override
  int version() {
    1
  }

  @Override
  String operationForProducer() {
    "kafka.send"
  }

  @Override
  String operationForConsumer() {
    return "kafka.process"
  }

  @Override
  String service() {
    return Config.get().getServiceName()
  }
}

class KafkaClientDataStreamsDisabledForkedTest extends KafkaClientTestBase {
  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig("dd.service", "KafkaClientDataStreamsDisabledForkedTest")
    injectSysConfig("dd.kafka.legacy.tracing.enabled", "true")
  }

  @Override
  String service() {
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

  @Override
  boolean isDataStreamsEnabled() {
    return false
  }
}

class KafkaClientContextSwapForkedTest extends KafkaClientV0ForkedTest {
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig(TraceInstrumentationConfig.LEGACY_CONTEXT_MANAGER_ENABLED, "false")
  }
}

// consumer-scope deferral turned on in LEGACY context-manager mode (the only mode the flag defers
// in) -- the last record's consume span is deferred past the poll loop instead of closed in-loop. A
// short scope-iteration keep-alive lets the native RootIterationScopeCleaner reap a lingering span
// quickly when no explicit trigger arrives.
class KafkaClientCreateConsumerScopeForkedTest extends KafkaClientV0ForkedTest {
  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig(TraceInstrumentationConfig.KAFKA_CREATE_CONSUMER_SCOPE_ENABLED, "true")
    injectSysConfig(TracerConfig.SCOPE_ITERATION_KEEP_ALIVE, "1")
  }

  @Override
  boolean deferConsumerScope() {
    true
  }
}

// flag on but with the NEW context-swap manager active, the deferred consumer scope is
// intentionally NOT engaged -- that manager has no native cross-thread-safe cleanup for a lingering
// scope -- so behavior matches flag-off. deferConsumerScope() stays false to assert that.
class KafkaClientCreateConsumerScopeContextSwapForkedTest extends KafkaClientCreateConsumerScopeForkedTest {
  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig(TraceInstrumentationConfig.LEGACY_CONTEXT_MANAGER_ENABLED, "false")
  }

  @Override
  boolean deferConsumerScope() {
    false
  }
}

class KafkaClientBadBase64HeaderForkedTest extends InstrumentationSpecification {
  EmbeddedKafkaBroker embeddedKafka

  def setup() {
    embeddedKafka = new EmbeddedKafkaKraftBroker(1, 2, KafkaClientTestBase.SHARED_TOPIC)
    embeddedKafka.afterPropertiesSet()
  }

  def cleanup() {
    embeddedKafka.destroy()
  }

  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig(TraceInstrumentationConfig.KAFKA_CLIENT_BASE64_DECODING_ENABLED, "true")
    injectSysConfig(TracerConfig.HEADER_TAGS, "x-custom-header:my.custom.tag")
  }

  def "producer span is created when message carries non-Base64 headers and base64 decoding is enabled"() {
    setup:
    def producerProps = KafkaTestUtils.producerProps(embeddedKafka.getBrokersAsString())
    def producer = new KafkaProducer<String, String>(producerProps, new StringSerializer(), new StringSerializer())

    when:
    def headers = new RecordHeaders([
      new RecordHeader("x-custom-header", "not-valid-base64!@#".getBytes(StandardCharsets.UTF_8)),
      new RecordHeader("x-another-header", "also-not-base64!!".getBytes(StandardCharsets.UTF_8))
    ])
    producer.send(new ProducerRecord<>(KafkaClientTestBase.SHARED_TOPIC, 0, null, "hello", headers)).get()

    then:
    TEST_WRITER.waitForTraces(1)
    !TEST_WRITER.isEmpty()

    cleanup:
    producer?.close()
  }
}

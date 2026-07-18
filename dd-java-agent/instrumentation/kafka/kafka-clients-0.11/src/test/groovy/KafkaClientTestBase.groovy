import datadog.trace.api.datastreams.DataStreamsTags
import datadog.trace.api.datastreams.DataStreamsTransactionExtractor
import datadog.trace.api.config.TraceInstrumentationConfig
import datadog.trace.api.config.TracerConfig
import datadog.trace.instrumentation.kafka_common.ClusterIdHolder

import static datadog.trace.agent.test.utils.TraceUtils.basicSpan
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.isAsyncPropagationEnabled

import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.agent.test.naming.VersionedNamingTestBase
import datadog.trace.api.Config
import datadog.trace.api.DDTags
import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.common.writer.ListWriter
import datadog.trace.core.DDSpan
import datadog.trace.core.datastreams.StatsGroup
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
import org.springframework.kafka.listener.BatchMessageListener
import org.springframework.kafka.listener.KafkaMessageListenerContainer
import org.springframework.kafka.listener.MessageListener
import org.springframework.kafka.test.rule.KafkaEmbedded
import org.springframework.kafka.test.utils.ContainerTestUtils
import org.springframework.kafka.test.utils.KafkaTestUtils
import spock.lang.IgnoreIf
import spock.lang.Shared

import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

abstract class KafkaClientTestBase extends VersionedNamingTestBase {
  static final SHARED_TOPIC = "shared.topic"

  KafkaEmbedded embeddedKafka

  @Override
  void configurePreAgent() {
    super.configurePreAgent()

    injectSysConfig("dd.kafka.e2e.duration.enabled", "true")
  }

  public static final LinkedHashMap<String, String> PRODUCER_PATHWAY_EDGE_TAGS

  // filter out Kafka poll, since the function is called in a loop, giving inconsistent results
  @Shared
  static final ListWriter.Filter DROP_KAFKA_POLL = new ListWriter.Filter() {
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

  private static class SortBatchKafkaTraces implements Comparator<List<DDSpan>> {
    @Override
    int compare(List<DDSpan> o1, List<DDSpan> o2) {
      return Long.compare(batchSortKey(o1), batchSortKey(o2))
    }
  }

  private static long batchSortKey(List<DDSpan> trace) {
    assert !trace.isEmpty()
    if (trace.get(0).localRootSpan.operationName.toString() == "parent") {
      return Long.MIN_VALUE
    }
    def deliverSpan = trace.find { it.operationName.toString() == "kafka.deliver" }
    return deliverSpan ? deliverSpan.parentId : trace.get(0).parentId
  }

  private static List<DDSpan> producerSpans(List<List<DDSpan>> traces) {
    def producerTrace = traces.find { trace ->
      !trace.isEmpty() && trace.get(0).localRootSpan.operationName.toString() == "parent"
    }
    assert producerTrace != null
    return producerTrace
      .findAll { it.getTag(Tags.SPAN_KIND) == Tags.SPAN_KIND_PRODUCER }
      .sort { it.spanId }
  }


  static {
    PRODUCER_PATHWAY_EDGE_TAGS = new LinkedHashMap<>(3)
    PRODUCER_PATHWAY_EDGE_TAGS.put("direction", "out")
    PRODUCER_PATHWAY_EDGE_TAGS.put("topic", SHARED_TOPIC)
    PRODUCER_PATHWAY_EDGE_TAGS.put("type", "kafka")
  }

  def setup() {
    embeddedKafka = new KafkaEmbedded(1, true, SHARED_TOPIC)
    embeddedKafka.before()
    TEST_WRITER.setFilter(DROP_KAFKA_POLL)
  }

  def cleanup() {
    embeddedKafka?.after()
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

  // when true, the consumer-scope-deferring flag is enabled for this variant, so same-thread
  // post-loop work is expected to nest under the last record's consume span
  boolean deferConsumerScope() {
    false
  }

  String serviceForTimeInQueue() {
    "kafka"
  }

  abstract boolean hasQueueSpan()

  abstract boolean splitByDestination()

  @Override
  protected boolean isDataStreamsEnabled() {
    return true
  }

  def "test extracting avro schema"() {
    setup:
    def senderProps = KafkaTestUtils.senderProps(embeddedKafka.getBrokersAsString())
    Producer<String, AvroMock> producer = new KafkaProducer<>(senderProps, new StringSerializer(), new AvroMockSerializer())

    when:
    AvroMock message = new AvroMock("{\"name\":\"test\"}")
    runUnderTrace("parent") {
      producer.send(new ProducerRecord(SHARED_TOPIC, message)) { meta, ex ->
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
      TEST_DATA_STREAMS_WRITER.waitForGroups(1)
      TEST_DATA_STREAMS_WRITER.waitForBacklogs(1)
    }

    then:
    // check that the message was received
    assertTraces(1, SORT_TRACES_BY_ID) {
      trace(3) {
        basicSpan(it, "parent")
        basicSpan(it, "producer callback", span(0))
        producerSpan(it, senderProps, span(0), false, false, "{\"name\":\"test\"}")
      }
    }

    cleanup:
    producer.close()
  }

  def "test kafka produce and consume"() {
    setup:
    def senderProps = KafkaTestUtils.senderProps(embeddedKafka.getBrokersAsString())
    if (isDataStreamsEnabled()) {
      senderProps.put(ProducerConfig.METADATA_MAX_AGE_CONFIG, 1000)
    }
    TEST_WRITER.setFilter(dropEmptyKafkaPoll)
    KafkaProducer<String, String> producer = new KafkaProducer<>(senderProps, new StringSerializer(), new StringSerializer())
    String clusterId = ""
    if (isDataStreamsEnabled()) {
      producer.flush()
      clusterId = producer.metadata.cluster.clusterResource().clusterId()
      while (clusterId == null || clusterId.isEmpty()) {
        Thread.sleep(1500)
        clusterId = producer.metadata.cluster.clusterResource().clusterId()
      }
    }

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
      // wait for produce offset 0, commit offset 0 on partition 0 and 1, and commit offset 1 on 1 partition.
      TEST_DATA_STREAMS_WRITER.waitForBacklogs(3)
    }

    then:
    // check that the message was received
    def received = records.poll(5, TimeUnit.SECONDS)
    received.value() == greeting
    received.key() == null

    // verify ClusterIdHolder was properly cleaned up after produce and consume
    ClusterIdHolder.get() == null

    int nTraces = isDataStreamsEnabled() ? 3 : 2
    int produceTraceIdx = nTraces - 1
    TEST_WRITER.waitForTraces(nTraces)
    def traces = (Arrays.asList(TEST_WRITER.toArray()) as List<List<DDSpan>>)
    Collections.sort(traces, new SortKafkaTraces())
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
        trace(1, {
          pollSpan(it)
        })
      }
      trace(3) {
        basicSpan(it, "parent")
        basicSpan(it, "producer callback", span(0))
        producerSpan(it, senderProps, span(0), false)
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
      def items = new ArrayList<DataStreamsTags>(TEST_DATA_STREAMS_WRITER.backlogs).sort { it.type + it.partition}
      verifyAll(items) {
        size() == 3
        get(0).hasAllTags(
          "consumer_group:sender",
          "kafka_cluster_id:$clusterId",
          "partition:0",
          "topic:" + SHARED_TOPIC,
          "type:kafka_commit"
          )
        get(1).hasAllTags(
          "consumer_group:sender",
          "kafka_cluster_id:$clusterId",
          "partition:1",
          "topic:" + SHARED_TOPIC,
          "type:kafka_commit"
          )
        get(2).hasAllTags(
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
    def senderProps = KafkaTestUtils.senderProps(embeddedKafka.getBrokersAsString())
    // set a low max request size, so that we can crash it
    senderProps.put(ProducerConfig.MAX_REQUEST_SIZE_CONFIG, 10)
    Producer<String, String> producer = new KafkaProducer<>(senderProps, new StringSerializer(), new StringSerializer())

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
    def senderProps = KafkaTestUtils.senderProps(embeddedKafka.getBrokersAsString())
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
    if (isDataStreamsEnabled()) {
      TEST_DATA_STREAMS_WRITER.waitForGroups(2)
      // wait for produce offset 0, commit offset 0 on partition 0 and 1, and commit offset 1 on 1 partition.
      TEST_DATA_STREAMS_WRITER.waitForBacklogs(3)
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
      def items = new ArrayList<DataStreamsTags>(TEST_DATA_STREAMS_WRITER.backlogs).sort {it.type + it.partition}
      verifyAll(items) {
        size() == 3
        get(0).hasAllTags(
          "consumer_group:sender",
          "kafka_cluster_id:$clusterId".toString(),
          "partition:0",
          "topic:" + SHARED_TOPIC,
          "type:kafka_commit"
          )
        get(1).hasAllTags(
          "consumer_group:sender",
          "kafka_cluster_id:$clusterId".toString(),
          "partition:1",
          "topic:" + SHARED_TOPIC,
          "type:kafka_commit"
          )
        get(2).hasAllTags(
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

  // when a consumer buffers records and writes to the database *after* the per-record iterator
  // loop, the write span is disconnected from the consume span unless the scope is deferred
  def "test work done after the consume loop is disconnected from the consume span"() {
    setup:
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

    then:
    // the produced record was actually consumed (fail clearly here instead of timing out below)
    buffer.size() == 1
    // producer trace + consume trace (+ queue span, same trace) + the db.write trace -- when
    // deferConsumerScope() is enabled, the db.write merges into the still-open consume span's
    // trace instead of flushing as its own.
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

    if (!deferConsumerScope()) {
      // bug: the DB write is its own root trace, not a child of the consume span, and the consume
      // span is left childless
      assert dbWriteSpan.parentId == 0
      assert dbWriteSpan.traceId != consumeSpan.traceId
      assert spans.every {
        it.parentId != consumeSpan.spanId
      }
    } else {
      // fixed: the post-loop DB write nests under the deferred consume span
      assert dbWriteSpan.traceId == consumeSpan.traceId
      assert dbWriteSpan.parentId == consumeSpan.spanId
    }

    cleanup:
    consumer.close()
    producer.close()
  }

  // with N buffered records and a single post-loop flush, only the LAST record's consume span
  // should stay open long enough to parent the flush; records 1..N-1 still get independent spans
  @IgnoreIf({ !instance.deferConsumerScope() })
  def "test single post-loop write nests under the last record's consume span when consumer scope is deferred"() {
    setup:
    def kafkaPartition = 0
    def consumerProperties = KafkaTestUtils.consumerProps("sender", "false", embeddedKafka)
    consumerProperties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
    def consumer = new KafkaConsumer<String, String>(consumerProperties)

    def senderProps = KafkaTestUtils.senderProps(embeddedKafka.getBrokersAsString())
    def producer = new KafkaProducer(senderProps)

    consumer.assign(Arrays.asList(new TopicPartition(SHARED_TOPIC, kafkaPartition)))

    when:
    def recordCount = 3
    (0..<recordCount).each {
      producer.send(new ProducerRecord<Integer, String>(SHARED_TOPIC, kafkaPartition, null, "greeting-$it".toString())).get()
    }
    TEST_WRITER.waitForTraces(recordCount)
    def pollResult = KafkaTestUtils.getRecords(consumer)

    def buffer = []
    for (record in pollResult) {
      buffer.add(record.value())
    }
    // Single flush after the whole batch, standing in for a buffered DB write.
    runUnderTrace("db.write") {}

    then:
    buffer.size() == recordCount
    // recordCount producer traces + (recordCount - 1) independent early-record consume traces +
    // one merged trace for the last record's consume span and the db.write it now parents.
    TEST_WRITER.waitForTraces(2 * recordCount)
    List<DDSpan> spans = TEST_WRITER.flatten()
    def consumeSpans = spans.findAll {
      it.operationName.toString() == operationForConsumer()
    }.sort { it.getTag(InstrumentationTags.OFFSET) as int }
    def dbWriteSpan = spans.find {
      it.operationName.toString() == "db.write"
    }

    consumeSpans.size() == recordCount
    dbWriteSpan != null
    def lastConsumeSpan = consumeSpans.last()
    def earlierConsumeSpans = consumeSpans[0..-2]

    dbWriteSpan.traceId == lastConsumeSpan.traceId
    dbWriteSpan.parentId == lastConsumeSpan.spanId
    earlierConsumeSpans.every {
      it.traceId != lastConsumeSpan.traceId
    }

    cleanup:
    consumer?.close()
    producer?.close()
  }

  // close trigger #1: a second poll() must finish the previous batch's lingering consume span
  // (its trace flushes) without the new poll cycle's work nesting under it
  @IgnoreIf({ !instance.deferConsumerScope() })
  def "test a second poll() finishes the previous lingering consume span without nesting under it"() {
    setup:
    def kafkaPartition = 0
    def consumerProperties = KafkaTestUtils.consumerProps("sender", "false", embeddedKafka)
    consumerProperties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
    def consumer = new KafkaConsumer<String, String>(consumerProperties)

    def senderProps = KafkaTestUtils.senderProps(embeddedKafka.getBrokersAsString())
    def producer = new KafkaProducer(senderProps)

    consumer.assign(Arrays.asList(new TopicPartition(SHARED_TOPIC, kafkaPartition)))

    when:
    producer.send(new ProducerRecord<Integer, String>(SHARED_TOPIC, kafkaPartition, null, "first")).get()
    TEST_WRITER.waitForTraces(1)
    def firstBatch = KafkaTestUtils.getRecords(consumer)
    for (record in firstBatch) {
      // no-op: iterating to the end is what arms the deferred close for this batch's last record
    }
    DDSpan lingering = (DDSpan) activeSpan()

    // Send from a separate thread: the first record's consume span is deliberately still active
    // (lingering) on this thread, and we don't want it to become the ambient parent of the
    // "second" message's own producer span (which would then propagate into its consume span via
    // the Kafka header trace context and defeat this test's own assertions below).
    Thread.start {
      producer.send(new ProducerRecord<Integer, String>(SHARED_TOPIC, kafkaPartition, null, "second")).get()
    }.join()
    TEST_WRITER.waitForTraces(2)
    def secondBatch = KafkaTestUtils.getRecords(consumer)
    def secondValues = []
    for (record in secondBatch) {
      secondValues.add(record.value())
    }
    // the second batch's only record is itself now the last record of its loop, so it is left
    // lingering too (armed for its own deferred close) -- grab it directly instead of relying on
    // TEST_WRITER, which won't have flushed it yet.
    DDSpan secondLingering = (DDSpan) activeSpan()

    then:
    firstBatch.count() == 1
    secondValues == ["second"]
    lingering != null
    lingering.operationName.toString() == operationForConsumer()
    // the second poll() closes the first batch's lingering span, flushing its trace, without the
    // second batch's own (still-lingering) consume span nesting under it
    TEST_WRITER.waitUntilReported(lingering, 10, TimeUnit.SECONDS)
    lingering.isFinished()
    secondLingering != null
    secondLingering.operationName.toString() == operationForConsumer()
    secondLingering.traceId != lingering.traceId
    secondLingering.parentId != lingering.spanId

    cleanup:
    consumer?.close()
    producer?.close()
  }

  // close trigger #2: with no further poll(), consumer.close() must finish a lingering consume span
  @IgnoreIf({ !instance.deferConsumerScope() })
  def "test consumer close() finishes a lingering consume span"() {
    setup:
    def kafkaPartition = 0
    def consumerProperties = KafkaTestUtils.consumerProps("sender", "false", embeddedKafka)
    consumerProperties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
    def consumer = new KafkaConsumer<String, String>(consumerProperties)

    def senderProps = KafkaTestUtils.senderProps(embeddedKafka.getBrokersAsString())
    def producer = new KafkaProducer(senderProps)

    consumer.assign(Arrays.asList(new TopicPartition(SHARED_TOPIC, kafkaPartition)))

    when:
    producer.send(new ProducerRecord<Integer, String>(SHARED_TOPIC, kafkaPartition, null, "greeting")).get()
    TEST_WRITER.waitForTraces(1)
    def pollResult = KafkaTestUtils.getRecords(consumer)
    for (record in pollResult) {
      // no-op: iterating to the end is what arms the deferred close for the last record
    }
    DDSpan lingering = (DDSpan) activeSpan()
    consumer.close()

    then:
    pollResult.count() == 1
    lingering != null
    lingering.operationName.toString() == operationForConsumer()
    TEST_WRITER.waitUntilReported(lingering, 10, TimeUnit.SECONDS)
    lingering.isFinished()

    cleanup:
    producer?.close()
  }

  // owner-aware cleanup: a consume span deferred while iterating on one thread must still be
  // finished by a close() that runs on a different thread, where it is not top-of-stack
  @IgnoreIf({ !instance.deferConsumerScope() })
  def "test a consume span deferred on a worker thread is finished by a close on another thread"() {
    setup:
    def kafkaPartition = 0
    def consumerProperties = KafkaTestUtils.consumerProps("sender", "false", embeddedKafka)
    consumerProperties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
    def consumer = new KafkaConsumer<String, String>(consumerProperties)

    def senderProps = KafkaTestUtils.senderProps(embeddedKafka.getBrokersAsString())
    def producer = new KafkaProducer(senderProps)

    consumer.assign(Arrays.asList(new TopicPartition(SHARED_TOPIC, kafkaPartition)))

    when:
    producer.send(new ProducerRecord<Integer, String>(SHARED_TOPIC, kafkaPartition, null, "greeting")).get()
    TEST_WRITER.waitForTraces(1)
    def pollResult = KafkaTestUtils.getRecords(consumer)

    // iterate on a worker thread so the deferred consume span is left active there, never
    // top-of-stack on the main thread that closes the consumer
    DDSpan lingering = null
    Thread.start {
      for (record in pollResult) {
        // no-op: iterating to the end arms the deferred close for the last record
      }
      lingering = (DDSpan) activeSpan()
    }.join()

    consumer.close()

    then:
    pollResult.count() == 1
    lingering != null
    lingering.operationName.toString() == operationForConsumer()
    // only the per-consumer owner-aware handle can reach this span from the closing thread
    TEST_WRITER.waitUntilReported(lingering, 10, TimeUnit.SECONDS)
    lingering.isFinished()

    cleanup:
    producer?.close()
  }

  // leak safety: with no further poll(), close(), or unsubscribe(), the native
  // RootIterationScopeCleaner (scope-iteration keep-alive) must still eventually finish a lingering
  // consume span within a bounded window
  @IgnoreIf({ !instance.deferConsumerScope() })
  def "test a lingering consume span is eventually finished with no further trigger"() {
    setup:
    def kafkaPartition = 0
    def consumerProperties = KafkaTestUtils.consumerProps("sender", "false", embeddedKafka)
    consumerProperties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
    def consumer = new KafkaConsumer<String, String>(consumerProperties)

    def senderProps = KafkaTestUtils.senderProps(embeddedKafka.getBrokersAsString())
    def producer = new KafkaProducer(senderProps)

    consumer.assign(Arrays.asList(new TopicPartition(SHARED_TOPIC, kafkaPartition)))

    when:
    producer.send(new ProducerRecord<Integer, String>(SHARED_TOPIC, kafkaPartition, null, "greeting")).get()
    TEST_WRITER.waitForTraces(1)
    def pollResult = KafkaTestUtils.getRecords(consumer)
    for (record in pollResult) {
      // no-op: iterating to the end is what defers the last record's consume scope
    }
    DDSpan lingering = (DDSpan) activeSpan()

    then:
    pollResult.count() == 1
    lingering != null
    !lingering.isFinished()
    // no further poll()/close()/unsubscribe() ever arrives -- the native iteration-scope cleaner
    // (keep-alive) is what finishes this
    TEST_WRITER.waitUntilReported(lingering, 10, TimeUnit.SECONDS)
    lingering.isFinished()

    cleanup:
    consumer?.close()
    producer?.close()
  }

  // committing offsets must NOT be treated as a close trigger -- the lingering consume span must
  // still be open immediately after a commitSync() call
  @IgnoreIf({ !instance.deferConsumerScope() })
  def "test committing offsets does not finish a lingering consume span"() {
    setup:
    def kafkaPartition = 0
    def consumerProperties = KafkaTestUtils.consumerProps("sender", "false", embeddedKafka)
    consumerProperties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
    def consumer = new KafkaConsumer<String, String>(consumerProperties)

    def senderProps = KafkaTestUtils.senderProps(embeddedKafka.getBrokersAsString())
    def producer = new KafkaProducer(senderProps)

    consumer.assign(Arrays.asList(new TopicPartition(SHARED_TOPIC, kafkaPartition)))

    when:
    producer.send(new ProducerRecord<Integer, String>(SHARED_TOPIC, kafkaPartition, null, "greeting")).get()
    TEST_WRITER.waitForTraces(1)
    def pollResult = KafkaTestUtils.getRecords(consumer)
    for (record in pollResult) {
      // no-op: iterating to the end is what arms the deferred close for the last record
    }
    DDSpan lingering = (DDSpan) activeSpan()
    consumer.commitSync()

    then:
    pollResult.count() == 1
    lingering != null
    !lingering.isFinished()

    cleanup:
    consumer?.close()
    producer?.close()
  }

  def "test spring kafka template produce and batch consume"() {
    setup:
    def senderProps = KafkaTestUtils.senderProps(embeddedKafka.getBrokersAsString())
    if (isDataStreamsEnabled()) {
      senderProps.put(ProducerConfig.METADATA_MAX_AGE_CONFIG, 1000)
    }
    def producerFactory = new DefaultKafkaProducerFactory<String, String>(senderProps)
    def kafkaTemplate = new KafkaTemplate<String, String>(producerFactory)
    String clusterId = null
    if (isDataStreamsEnabled()) {
      clusterId = waitForKafkaMetadataUpdate(kafkaTemplate)
    }

    def consumerProperties = KafkaTestUtils.consumerProps("sender", "false", embeddedKafka)
    def consumerFactory = new DefaultKafkaConsumerFactory<String, String>(consumerProperties)
    def containerProperties = containerProperties()


    def container = new KafkaMessageListenerContainer<>(consumerFactory, containerProperties)
    def records = new LinkedBlockingQueue<ConsumerRecord<String, String>>()
    container.setupMessageListener(new BatchMessageListener<String, String>() {
      @Override
      void onMessage(List<ConsumerRecord<String, String>> consumerRecords) {
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
        }, {
          ex ->
          runUnderTrace("producer exception: " + ex) {}
        })
      }
      blockUntilChildSpansFinished(2 * greetings.size())
    }
    if (isDataStreamsEnabled()) {
      TEST_DATA_STREAMS_WRITER.waitForGroups(2)
      // wait for produce offset 0, commit offset 0 on partition 0 and 1, and commit offset 1 on 1 partition.
      TEST_DATA_STREAMS_WRITER.waitForBacklogs(4)
    }

    then:
    def receivedSet = greetings.toSet()
    def receivedRecords = []
    greetings.eachWithIndex {
      g, i ->
      def received = records.poll(5, TimeUnit.SECONDS)
      receivedSet.remove(received.value()) //maybe received out of order in case several partitions
      assert received.key() == null

      def headers = received.headers()
      assert headers.iterator().hasNext()
      receivedRecords.add(received)
    }
    assert receivedSet.isEmpty()

    TEST_WRITER.waitForTraces(4)
    def traces = Arrays.asList(TEST_WRITER.toArray()) as List<List<DDSpan>>
    def produceSpans = producerSpans(traces)
    def spanIdToRecord = receivedRecords.collectEntries {
      record ->
      def header = record.headers().headers("x-datadog-parent-id").iterator()
      assert header.hasNext()
      [(Long.parseLong(new String(header.next().value(), StandardCharsets.UTF_8))): record]
    }

    // Batch listener delivery order can vary; match each consumer trace to its producer via the propagated parent ID.
    assertTraces(4, new SortBatchKafkaTraces()) {
      trace(7) {
        basicSpan(it, "parent")
        basicSpan(it, "producer callback", span(0))
        producerSpan(it, senderProps, span(0), false)
        basicSpan(it, "producer callback", span(0))
        producerSpan(it, senderProps, span(0), false)
        basicSpan(it, "producer callback", span(0))
        producerSpan(it, senderProps, span(0), false)
      }

      if (hasQueueSpan()) {
        [0, 1, 2].each {
          i ->
          def expectedOffset = spanIdToRecord[produceSpans[i].spanId].offset()
          trace(2) {
            consumerSpan(it, consumerProperties, span(1), expectedOffset..expectedOffset)
            queueSpan(it, produceSpans[i])
          }
        }
      } else {
        [0, 1, 2].each {
          i ->
          def expectedOffset = spanIdToRecord[produceSpans[i].spanId].offset()
          trace(1) {
            consumerSpan(it, consumerProperties, produceSpans[i], expectedOffset..expectedOffset)
          }
        }
      }
    }

    if (isDataStreamsEnabled()) {
      StatsGroup first = TEST_DATA_STREAMS_WRITER.groups.find {
        it.parentHash == 0
      }
      verifyAll(first) {
        tags.hasAllTags("direction:out", "kafka_cluster_id:$clusterId", "topic:$SHARED_TOPIC".toString(), "type:kafka")
      }

      StatsGroup second = TEST_DATA_STREAMS_WRITER.groups.find {
        it.parentHash == first.hash
      }
      verifyAll(second) {
        tags.hasAllTags(
        "direction:in",
        "group:sender",
        "kafka_cluster_id:$clusterId",
        "topic:$SHARED_TOPIC".toString(),
        "type:kafka"
        )
      }
    }

    cleanup:
    producerFactory.stop()
    container?.stop()
  }

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
    value   | expected
    "false" | false
    "true"  | true
  }

  def "test producer extracts and uses existing trace context from record headers"() {
    setup:
    def senderProps = KafkaTestUtils.senderProps(embeddedKafka.getBrokersAsString())
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

  def "test producer DSM transaction tracking extracts transaction id from headers"() {
    setup:
    if (!isDataStreamsEnabled()) {
      return
    }

    injectEnvConfig("DD_DATA_STREAMS_ENABLED", "true")

    // Configure a DSM transaction extractor for KAFKA_PRODUCE_HEADERS
    def extractorsByTypeField = TEST_DATA_STREAMS_MONITORING.getClass().getDeclaredField("extractorsByType")
    extractorsByTypeField.setAccessible(true)
    def oldExtractorsByType = extractorsByTypeField.get(TEST_DATA_STREAMS_MONITORING)

    def extractor = new DataStreamsTransactionExtractor() {
      String getName() {
        return "kafka-produce-test"
      }
      DataStreamsTransactionExtractor.Type getType() {
        return DataStreamsTransactionExtractor.Type.KAFKA_PRODUCE_HEADERS
      }
      String getValue() {
        return "x-transaction-id"
      }
    }
    def extractorsByType = new EnumMap<>(DataStreamsTransactionExtractor.Type)
    extractorsByType.put(DataStreamsTransactionExtractor.Type.KAFKA_PRODUCE_HEADERS, [extractor])
    extractorsByTypeField.set(TEST_DATA_STREAMS_MONITORING, extractorsByType)

    def senderProps = KafkaTestUtils.senderProps(embeddedKafka.getBrokersAsString())
    def producer = new KafkaProducer<>(senderProps, new StringSerializer(), new StringSerializer())

    def headers = new RecordHeaders()
    headers.add(new RecordHeader("x-transaction-id", "txn-123".getBytes(StandardCharsets.UTF_8)))

    when:
    def record = new ProducerRecord(SHARED_TOPIC, 0, null, "test-dsm-transaction", headers)
    producer.send(record).get()

    then:
    TEST_WRITER.waitForTraces(1)
    def producedSpan = TEST_WRITER[0][0]
    producedSpan.getTag(Tags.DSM_TRANSACTION_ID) == "txn-123"
    producedSpan.getTag(Tags.DSM_TRANSACTION_CHECKPOINT) == "kafka-produce-test"

    cleanup:
    extractorsByTypeField?.set(TEST_DATA_STREAMS_MONITORING, oldExtractorsByType)
    producer?.close()
  }

  def "test consumer DSM transaction tracking extracts transaction id from headers"() {
    setup:
    if (!isDataStreamsEnabled()) {
      return
    }

    injectEnvConfig("DD_DATA_STREAMS_ENABLED", "true")

    // Configure a DSM transaction extractor for KAFKA_CONSUME_HEADERS
    def extractorsByTypeField = TEST_DATA_STREAMS_MONITORING.getClass().getDeclaredField("extractorsByType")
    extractorsByTypeField.setAccessible(true)
    def oldExtractorsByType = extractorsByTypeField.get(TEST_DATA_STREAMS_MONITORING)

    def extractor = new DataStreamsTransactionExtractor() {
      String getName() {
        return "kafka-consume-test"
      }
      DataStreamsTransactionExtractor.Type getType() {
        return DataStreamsTransactionExtractor.Type.KAFKA_CONSUME_HEADERS
      }
      String getValue() {
        return "x-transaction-id"
      }
    }
    def extractorsByType = new EnumMap<>(DataStreamsTransactionExtractor.Type)
    extractorsByType.put(DataStreamsTransactionExtractor.Type.KAFKA_CONSUME_HEADERS, [extractor])
    extractorsByTypeField.set(TEST_DATA_STREAMS_MONITORING, extractorsByType)

    def kafkaPartition = 0
    def consumerProperties = KafkaTestUtils.consumerProps("sender", "false", embeddedKafka)
    consumerProperties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
    def consumer = new KafkaConsumer<String, String>(consumerProperties)

    def senderProps = KafkaTestUtils.senderProps(embeddedKafka.getBrokersAsString())
    def producer = new KafkaProducer<>(senderProps, new StringSerializer(), new StringSerializer())

    consumer.assign(Arrays.asList(new TopicPartition(SHARED_TOPIC, kafkaPartition)))

    def headers = new RecordHeaders()
    headers.add(new RecordHeader("x-transaction-id", "txn-456".getBytes(StandardCharsets.UTF_8)))

    when:
    def record = new ProducerRecord(SHARED_TOPIC, kafkaPartition, null, "test-dsm-consume-transaction", headers)
    producer.send(record).get()

    then:
    TEST_WRITER.waitForTraces(1)
    def pollResult = KafkaTestUtils.getRecords(consumer)
    def recs = pollResult.records(new TopicPartition(SHARED_TOPIC, kafkaPartition)).iterator()
    recs.hasNext()
    recs.next().value() == "test-dsm-consume-transaction"
    !recs.hasNext()

    // The consume span is created by TracingIterator when iterating over records
    // Find the consumer span with the DSM transaction tags
    TEST_WRITER.waitForTraces(2)
    def allTraces = TEST_WRITER.toArray() as List<List<DDSpan>>
    def consumerSpan = allTraces.collectMany {
      it
    }.find {
      it.getTag(Tags.DSM_TRANSACTION_ID) == "txn-456"
    }
    consumerSpan != null
    consumerSpan.getTag(Tags.DSM_TRANSACTION_ID) == "txn-456"
    consumerSpan.getTag(Tags.DSM_TRANSACTION_CHECKPOINT) == "kafka-consume-test"

    cleanup:
    extractorsByTypeField?.set(TEST_DATA_STREAMS_MONITORING, oldExtractorsByType)
    consumer?.close()
    producer?.close()
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
          // in v0 the service name is always set to DD_SERVICE while it should just be unset as v1
          // this is a buggy behaviour that could not be easily fixed.
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
    String clusterId = producer.metadata.cluster.clusterResource().clusterId()
    while (clusterId == null || clusterId.isEmpty()) {
      Thread.sleep(1500)
      clusterId = producer.metadata.cluster.clusterResource().clusterId()
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

// exercises DD_TRACE_KAFKA_CREATE_CONSUMER_SCOPE_ENABLED=true in LEGACY context-manager mode (the
// only mode the flag defers in). A short scope-iteration keep-alive is injected so the native
// RootIterationScopeCleaner reaps a lingering consume span quickly when no further trigger arrives.
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

// with the flag on but the NEW context-swap manager active, the deferred consumer scope is
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
  KafkaEmbedded embeddedKafka

  def setup() {
    embeddedKafka = new KafkaEmbedded(1, true, KafkaClientTestBase.SHARED_TOPIC)
    embeddedKafka.before()
  }

  def cleanup() {
    embeddedKafka?.after()
  }

  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig(TraceInstrumentationConfig.KAFKA_CLIENT_BASE64_DECODING_ENABLED, "true")
    injectSysConfig(TracerConfig.HEADER_TAGS, "x-custom-header:my.custom.tag")
  }

  def "producer span is created when message carries non-Base64 headers and base64 decoding is enabled"() {
    setup:
    def senderProps = KafkaTestUtils.senderProps(embeddedKafka.getBrokersAsString())
    def producer = new KafkaProducer<String, String>(senderProps, new StringSerializer(), new StringSerializer())

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

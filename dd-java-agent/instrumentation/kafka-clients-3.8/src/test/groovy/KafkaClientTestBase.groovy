import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.agent.test.naming.VersionedNamingTestBase
import datadog.trace.api.Config
import datadog.trace.api.DDTags
import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.common.writer.ListWriter
import datadog.trace.core.DDSpan
import datadog.trace.core.datastreams.StatsGroup
import datadog.trace.test.util.Flaky
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.Producer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.StringSerializer
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.listener.ContainerProperties
import org.springframework.kafka.listener.KafkaMessageListenerContainer
import org.springframework.kafka.listener.MessageListener
import org.springframework.kafka.test.utils.ContainerTestUtils
import org.springframework.kafka.test.utils.KafkaTestUtils
import org.testcontainers.containers.KafkaContainer
import org.testcontainers.utility.DockerImageName

import java.util.concurrent.ExecutionException
import java.util.concurrent.Future

import static datadog.trace.agent.test.utils.TraceUtils.basicSpan

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeScope
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan

abstract class KafkaClientTestBase extends VersionedNamingTestBase {
  static final SHARED_TOPIC = "shared.topic"
  static final String MESSAGE = "Testing without headers for certain topics"

  static final dataTable() {
    [
      ["topic1,topic2,topic3,topic4", false, false, false, false],
      ["topic1,topic2", false, false, true, true],
      ["topic1", false, true, true, true],
      ["", true, true, true, true],
      ["randomTopic", true, true, true, true]
    ]
  }

  @Override
  boolean useStrictTraceWrites() {
    // TODO fix this by making sure that spans get closed properly
    return false
  }

  @Override
  void configurePreAgent() {
    super.configurePreAgent()

    injectSysConfig("dd.kafka.e2e.duration.enabled", "true")
    injectSysConfig("dd.trace.experimental.kafka.enabled","true")
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

  def setup() {
    TEST_WRITER.setFilter(dropKafkaPoll)
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

  abstract boolean hasQueueSpan()

  abstract boolean splitByDestination()

  @Override
  protected boolean isDataStreamsEnabled() {
    return true
  }

  @Flaky
  def "test kafka produce and consume"() {
    setup:
    // Create and start a Kafka container using Testcontainers
    KafkaContainer kafkaContainer = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:latest")).withEmbeddedZookeeper().withEnv("KAFKA_CREATE_TOPICS", SHARED_TOPIC)
    kafkaContainer.start()

    def senderProps = KafkaTestUtils.producerProps(kafkaContainer.getBootstrapServers())
    if (isDataStreamsEnabled()) {
      senderProps.put(ProducerConfig.METADATA_MAX_AGE_CONFIG, 1000)
    }
    TEST_WRITER.setFilter(dropEmptyKafkaPoll)
    KafkaProducer<String, String> producer = new KafkaProducer<>(senderProps, new StringSerializer(), new StringSerializer())
    String clusterId = ""
    if (isDataStreamsEnabled()) {
      producer.flush()
      clusterId = producer.metadata.fetch().clusterResource().clusterId()
      while (clusterId == null || clusterId.isEmpty()) {
        Thread.sleep(1500)
        clusterId = producer.metadata.fetch().clusterResource().clusterId()
      }
    }

    // set up the Kafka consumer properties
    def consumerProperties = KafkaTestUtils.consumerProps( kafkaContainer.getBootstrapServers(),"sender", "false")
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
          TEST_WRITER.waitForTraces(1)
          // ensure consistent ordering of traces
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
      producer.send(new ProducerRecord(SHARED_TOPIC,greeting)) { meta, ex ->
        assert activeScope().isAsyncPropagating()
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
        trace(1, { pollSpan(it) })
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
        edgeTags == ["direction:out", "kafka_cluster_id:$clusterId", "topic:$SHARED_TOPIC".toString(), "type:kafka"]
        edgeTags.size() == 4
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
      }
      List<String> produce = [
        "kafka_cluster_id:$clusterId",
        "partition:"+received.partition(),
        "topic:"+SHARED_TOPIC,
        "type:kafka_produce"
      ]
      List<String> commit = [
        "consumer_group:sender",
        "kafka_cluster_id:$clusterId",
        "partition:"+received.partition(),
        "topic:$SHARED_TOPIC",
        "type:kafka_commit"
      ]
      verifyAll(TEST_DATA_STREAMS_WRITER.backlogs) {
        contains(new AbstractMap.SimpleEntry<List<String>, Long>(commit, 1).toString())
        contains(new AbstractMap.SimpleEntry<List<String>, Long>(produce, 0).toString())
      }
    }

    cleanup:
    producer.close()
    container?.stop()
    kafkaContainer.stop()
  }

  @Flaky
  def "test producing message too large"() {
    setup:
    // set a low max request size, so that we can crash it
    KafkaContainer kafkaContainer = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:latest")).withEmbeddedZookeeper().withEnv("KAFKA_CREATE_TOPICS", SHARED_TOPIC)
    kafkaContainer.start()

    def senderProps = KafkaTestUtils.producerProps(kafkaContainer.getBootstrapServers())
    senderProps.put(ProducerConfig.MAX_REQUEST_SIZE_CONFIG, 10)
    KafkaProducer<String, String> producer = new KafkaProducer<>(senderProps, new StringSerializer(), new StringSerializer())


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

  @Flaky
  def "test spring kafka template produce and consume"() {
    setup:
    KafkaContainer kafkaContainer = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:latest")).withEmbeddedZookeeper().withEnv("KAFKA_CREATE_TOPICS", SHARED_TOPIC)
    kafkaContainer.start()

    def senderProps = KafkaTestUtils.producerProps(kafkaContainer.getBootstrapServers())
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
    def consumerProperties = KafkaTestUtils.consumerProps( kafkaContainer.getBootstrapServers(),"sender", "false")

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
          TEST_WRITER.waitForTraces(1) // ensure consistent ordering of traces
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
        assert activeScope().isAsyncPropagating()
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

    def headers = received.headers()
    headers.iterator().hasNext()
    new String(headers.headers("x-datadog-trace-id").iterator().next().value()) == "${TEST_WRITER[0][2].traceId}"
    new String(headers.headers("x-datadog-parent-id").iterator().next().value()) == "${TEST_WRITER[0][2].spanId}"

    if (isDataStreamsEnabled()) {
      StatsGroup first = TEST_DATA_STREAMS_WRITER.groups.find { it.parentHash == 0 }
      verifyAll(first) {
        edgeTags == [
          "direction:out",
          "kafka_cluster_id:$clusterId".toString(),
          "topic:$SHARED_TOPIC".toString(),
          "type:kafka"
        ]
        edgeTags.size() == 4
      }

      StatsGroup second = TEST_DATA_STREAMS_WRITER.groups.find { it.parentHash == first.hash }
      verifyAll(second) {
        edgeTags == [
          "direction:in",
          "group:sender",
          "kafka_cluster_id:$clusterId".toString(),
          "topic:$SHARED_TOPIC".toString(),
          "type:kafka"
        ]
        edgeTags.size() == 5
      }
      List<String> produce = [
        "kafka_cluster_id:$clusterId".toString(),
        "partition:"+received.partition(),
        "topic:"+SHARED_TOPIC,
        "type:kafka_produce"
      ]
      List<String> commit = [
        "consumer_group:sender",
        "kafka_cluster_id:$clusterId".toString(),
        "partition:"+received.partition(),
        "topic:"+SHARED_TOPIC,
        "type:kafka_commit"
      ]
      verifyAll(TEST_DATA_STREAMS_WRITER.backlogs) {
        contains(new AbstractMap.SimpleEntry<List<String>, Long>(commit, 1).toString())
        contains(new AbstractMap.SimpleEntry<List<String>, Long>(produce, 0).toString())
      }
    }

    cleanup:
    producerFactory.stop()
    container?.stop()
    kafkaContainer.stop()
  }

  @Flaky
  def "test pass through tombstone"() {
    setup:
    KafkaContainer kafkaContainer = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:latest")).withEmbeddedZookeeper().withEnv("KAFKA_CREATE_TOPICS", SHARED_TOPIC)
    kafkaContainer.start()

    def senderProps = KafkaTestUtils.producerProps(kafkaContainer.getBootstrapServers())
    def producerFactory = new DefaultKafkaProducerFactory<String, String>(senderProps)
    def kafkaTemplate = new KafkaTemplate<String, String>(producerFactory)

    // set up the Kafka consumer properties
    def consumerProperties = KafkaTestUtils.consumerProps( kafkaContainer.getBootstrapServers(),"sender", "false")

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
          TEST_WRITER.waitForTraces(1) // ensure consistent ordering of traces
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
    kafkaContainer.stop()

  }

  @Flaky
  def "test records(TopicPartition) kafka consume"() {
    setup:
    KafkaContainer kafkaContainer = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:latest")).withEmbeddedZookeeper().withEnv("KAFKA_CREATE_TOPICS", SHARED_TOPIC)
    kafkaContainer.start()

    // set up the Kafka consumer properties
    def kafkaPartition = 0
    def consumerProperties = KafkaTestUtils.consumerProps( kafkaContainer.getBootstrapServers(),"sender", "false")
    consumerProperties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
    def consumer = new KafkaConsumer<String, String>(consumerProperties)

    def senderProps = KafkaTestUtils.producerProps(kafkaContainer.getBootstrapServers())
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
    kafkaContainer.stop()


  }

  @Flaky
  def "test records(TopicPartition).subList kafka consume"() {
    setup:

    KafkaContainer kafkaContainer = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:latest")).withEmbeddedZookeeper().withEnv("KAFKA_CREATE_TOPICS", SHARED_TOPIC)
    kafkaContainer.start()

    def senderProps = KafkaTestUtils.producerProps(kafkaContainer.getBootstrapServers())
    def consumerProperties = KafkaTestUtils.consumerProps( kafkaContainer.getBootstrapServers(),"sender", "false")

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
    kafkaContainer.stop()

  }

  @Flaky
  def "test records(TopicPartition).forEach kafka consume"() {
    setup:
    KafkaContainer kafkaContainer = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:latest")).withEmbeddedZookeeper().withEnv("KAFKA_CREATE_TOPICS", SHARED_TOPIC)
    kafkaContainer.start()

    def senderProps = KafkaTestUtils.producerProps(kafkaContainer.getBootstrapServers())
    def consumerProperties = KafkaTestUtils.consumerProps( kafkaContainer.getBootstrapServers(),"sender", "false")

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
    kafkaContainer.stop()

  }

  @Flaky
  def "test iteration backwards over ConsumerRecords"() {
    setup:
    KafkaContainer kafkaContainer = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:latest")).withEmbeddedZookeeper().withEnv("KAFKA_CREATE_TOPICS", SHARED_TOPIC)
    kafkaContainer.start()

    def senderProps = KafkaTestUtils.producerProps(kafkaContainer.getBootstrapServers())
    def consumerProperties = KafkaTestUtils.consumerProps( kafkaContainer.getBootstrapServers(),"sender", "false")

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
    kafkaContainer.stop()

  }

  @Flaky
  def "test kafka client header propagation manual config"() {
    setup:
    KafkaContainer kafkaContainer = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:latest")).withEmbeddedZookeeper().withEnv("KAFKA_CREATE_TOPICS", SHARED_TOPIC)
    kafkaContainer.start()

    def senderProps = KafkaTestUtils.producerProps(kafkaContainer.getBootstrapServers())
    def consumerProperties = KafkaTestUtils.consumerProps( kafkaContainer.getBootstrapServers(),"sender", "false")

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
          TEST_WRITER.waitForTraces(1) // ensure consistent ordering of traces
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
    kafkaContainer.stop()

    where:
    value   | expected
    "false" | false
    "true"  | true
  }

  def producerSpan(
    TraceAssert trace,
    Map<String,?> config,
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
      tags {
        "$Tags.COMPONENT" "java-kafka"
        "$Tags.SPAN_KIND" Tags.SPAN_KIND_PRODUCER
        "$InstrumentationTags.KAFKA_BOOTSTRAP_SERVERS" config.get(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG)
        if (partitioned) {
          "$InstrumentationTags.PARTITION" { it >= 0 }
        }
        if (tombstone) {
          "$InstrumentationTags.TOMBSTONE" true
        }
        if ({isDataStreamsEnabled()}) {
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
        defaultTags()
      }
    }
  }

  def queueSpan(
    TraceAssert trace,
    DDSpan parentSpan = null
  ) {
    trace.span {
      serviceName splitByDestination() ? "$SHARED_TOPIC" :  serviceForTimeInQueue()
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
    Map<String,Object> config,
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
      tags {
        "$Tags.COMPONENT" "java-kafka"
        "$Tags.SPAN_KIND" Tags.SPAN_KIND_CONSUMER
        "$InstrumentationTags.PARTITION" { it >= 0 }
        "$InstrumentationTags.OFFSET" { offset.containsWithinBounds(it as int) }
        "$InstrumentationTags.CONSUMER_GROUP" "sender"
        "$InstrumentationTags.KAFKA_BOOTSTRAP_SERVERS" config.get(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG)
        "$InstrumentationTags.RECORD_QUEUE_TIME_MS" { it >= 0 }
        "$InstrumentationTags.RECORD_END_TO_END_DURATION_MS" { it >= 0 }
        if (tombstone) {
          "$InstrumentationTags.TOMBSTONE" true
        }
        if ({isDataStreamsEnabled()}) {
          "$DDTags.PATHWAY_HASH" { String }
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

class KafkaClientLegacyTracingV0ForkedTest extends KafkaClientLegacyTracingForkedTest{


}

class KafkaClientLegacyTracingV1ForkedTest extends KafkaClientLegacyTracingForkedTest{

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

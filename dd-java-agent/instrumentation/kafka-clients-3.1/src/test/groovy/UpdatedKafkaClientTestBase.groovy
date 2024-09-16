import datadog.trace.agent.test.naming.VersionedNamingTestBase
import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags
import datadog.trace.common.writer.ListWriter
import datadog.trace.core.DDSpan
import datadog.trace.core.datastreams.StatsGroup
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.StringSerializer
import org.junit.Rule
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.listener.KafkaMessageListenerContainer
import org.springframework.kafka.listener.MessageListener
import org.springframework.kafka.test.utils.ContainerTestUtils
import org.springframework.kafka.test.utils.KafkaTestUtils
import org.testcontainers.containers.KafkaContainer
import org.testcontainers.utility.DockerImageName

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

import static datadog.trace.agent.test.utils.TraceUtils.basicSpan
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeScope

abstract class UpdatedKafkaClientTestBase extends VersionedNamingTestBase {
  static final List<String> SHARED_TOPIC = List.of("topic1", "topic2", "topic3", "topic4")
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

  @Rule
  public KafkaContainer embeddedKafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:latest"))
  .withEmbeddedZookeeper()

  @Override
  void configurePreAgent() {
    super.configurePreAgent()

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

  def "test kafka produce and consume"() {
    setup:
    // Create and start a Kafka container using Testcontainers
    KafkaContainer kafkaContainer = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:latest"))
    kafkaContainer.start()

    def senderProps = KafkaTestUtils.senderProps(kafkaContainer.getBootstrapServers())
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
    def consumerProperties = KafkaTestUtils.consumerProps("sender", "false", kafkaContainer.getBootstrapServers())
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
          TEST_WRITER.waitForTraces(1)
          // ensure consistent ordering of traces
          records.add(record)
        }
      })
    // start the container and underlying message listener
    container.start()
    // wait until the container has the required number of assigned partitions
    ContainerTestUtils.waitForAssignment(container, kafkaContainer.getNumberOfPartitions())
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
    if (isDataStreamsEnabled()) {
      TEST_DATA_STREAMS_WRITER.waitForGroups(2)
      // wait for produce offset 0, commit offset 0 on partition 0 and 1, and commit offset 1 on 1 partition
    }

    then:
    // check that the message was received
    def received = records.poll(5, TimeUnit.SECONDS)
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


}
abstract class UpdatedKafkaClientForkedTest extends UpdatedKafkaClientTestBase {
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

class UKafkaClientV0ForkedTest extends UpdatedKafkaClientForkedTest {
  @Override
  String service() {
    return "KafkaClientTest"
  }
}

class UKafkaClientV1ForkedTest extends UpdatedKafkaClientForkedTest {
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

import datadog.trace.agent.test.naming.VersionedNamingTestBase
import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags
import datadog.trace.common.writer.ListWriter
import datadog.trace.core.DDSpan
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.StringSerializer
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.listener.ContainerProperties
import org.springframework.kafka.listener.KafkaMessageListenerContainer
import org.springframework.kafka.listener.MessageListener
import org.springframework.kafka.test.utils.ContainerTestUtils
import org.springframework.kafka.test.utils.KafkaTestUtils
import org.testcontainers.containers.KafkaContainer
import org.testcontainers.utility.DockerImageName

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

import static datadog.trace.agent.test.asserts.TagsAssert.assertTags
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeScope

class KafkaCodeOriginForkedTest extends VersionedNamingTestBase {
  static final SHARED_TOPIC = "shared.topic"
  static final String MESSAGE = "Testing without headers for certain topics"

  @Override
  boolean useStrictTraceWrites() {
    // TODO fix this by making sure that spans get closed properly
    return false
  }

  @Override
  void configurePreAgent() {
    super.configurePreAgent()

    injectSysConfig("dd.kafka.e2e.duration.enabled", "false")
    injectSysConfig("dd.kafka.legacy.tracing.enabled", "false")
    injectSysConfig("dd.service", "KafkaClientTest")
    codeOriginSetup()
  }

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

  def setup() {
    TEST_WRITER.setFilter(dropKafkaPoll)
  }

  @Override
  int version() {
    1
  }

  @Override
  String operation() {
    return null
  }

  String operationForProducer() {
    "kafka.send"
  }

  String operationForConsumer() {
    return "kafka.process"
  }

  String serviceForTimeInQueue() {
    "kafka-queue"
  }

  def "test with code origin"() {
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

    then:
    //    // check that the message was received
    def received = records.poll(10, TimeUnit.SECONDS)
    received.value() == greeting
    received.key() == null
    int nTraces = 2
    TEST_WRITER.waitForTraces(nTraces)
    def traces = (Arrays.asList(TEST_WRITER.toArray()) as List<List<DDSpan>>)
    Collections.sort(traces, new SortKafkaTraces())
    assertTags(traces[0][0], {
      it.codeOriginTags()
    }, false)

    cleanup:
    producer.close()
    container?.stop()
    kafkaContainer.stop()
  }

  @Override
  String service() {
    return "KafkaClientTest"
  }
}





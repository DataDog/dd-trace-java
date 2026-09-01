import static datadog.trace.agent.test.utils.TraceUtils.basicSpan
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan

import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.agent.test.naming.VersionedNamingTestBase
import datadog.trace.api.Config
import datadog.trace.api.DDTags
import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.common.writer.ListWriter
import datadog.trace.core.DDSpan
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.StringSerializer
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.listener.KafkaMessageListenerContainer
import org.springframework.kafka.listener.MessageListener
import org.springframework.kafka.test.rule.KafkaEmbedded
import org.springframework.kafka.test.utils.ContainerTestUtils
import org.springframework.kafka.test.utils.KafkaTestUtils
import spock.lang.Shared

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

abstract class KafkaClientTestBase extends VersionedNamingTestBase {
  static final SHARED_TOPIC = "shared.topic"

  KafkaEmbedded embeddedKafka

  // Filter out Kafka poll spans that appear due to the consumer's polling loop,
  // which gives inconsistent results across test runs.
  @Shared
  static final ListWriter.Filter DROP_KAFKA_POLL = new ListWriter.Filter() {
    @Override
    boolean accept(List<DDSpan> trace) {
      return !(trace.size() == 1 &&
        trace.get(0).getResourceName().toString().equals("kafka.poll"))
    }
  }

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
    def senderProps = KafkaTestUtils.senderProps(embeddedKafka.getBrokersAsString())
    if (isDataStreamsEnabled()) {
      senderProps.put(ProducerConfig.METADATA_MAX_AGE_CONFIG, 1000)
    }
    KafkaProducer<String, String> producer = new KafkaProducer<>(senderProps, new StringSerializer(), new StringSerializer())

    // set up Kafka consumer
    def consumerProperties = KafkaTestUtils.consumerProps("sender", "false", embeddedKafka)
    def consumerFactory = new DefaultKafkaConsumerFactory<String, String>(consumerProperties)
    def containerProperties = containerProperties()
    def container = new KafkaMessageListenerContainer<>(consumerFactory, containerProperties)
    def records = new LinkedBlockingQueue<ConsumerRecord<String, String>>()

    container.setupMessageListener(new MessageListener<String, String>() {
        @Override
        void onMessage(ConsumerRecord<String, String> record) {
          records.add(record)
        }
      })
    container.start()
    ContainerTestUtils.waitForAssignment(container, embeddedKafka.getPartitionsPerTopic())

    when:
    String greeting = "Hello Spring Kafka Sender!"
    runUnderTrace("parent") {
      producer.send(new ProducerRecord(SHARED_TOPIC, greeting)) { meta, ex ->
        if (ex == null) {
          runUnderTrace("producer callback") {}
        } else {
          runUnderTrace("producer exception: " + ex) {}
        }
      }
      blockUntilChildSpansFinished(2)
    }

    then:
    def received = records.poll(5, TimeUnit.SECONDS)
    received.value() == greeting
    received.key() == null

    assertTraces(2, new SortKafkaTraces()) {
      if (hasQueueSpan()) {
        trace(2) {
          consumerSpan(it, consumerProperties, span(1))
          queueSpan(it, trace(1)[2])
        }
      } else {
        trace(1) {
          consumerSpan(it, consumerProperties, trace(1)[2])
        }
      }
      trace(3) {
        basicSpan(it, "parent")
        basicSpan(it, "producer callback", span(0))
        producerSpan(it, senderProps, span(0), false)
      }
    }

    // Verify trace context was propagated via message headers
    def headers = received.headers()
    headers.iterator().hasNext()

    cleanup:
    producer.close()
    container?.stop()
  }

  def "test producing message too large"() {
    setup:
    def senderProps = KafkaTestUtils.senderProps(embeddedKafka.getBrokersAsString())
    senderProps.put(ProducerConfig.MAX_REQUEST_SIZE_CONFIG, 10)
    def producer = new KafkaProducer<>(senderProps, new StringSerializer(), new StringSerializer())

    when:
    String greeting = "Hello Spring Kafka"
    def future = producer.send(new ProducerRecord(SHARED_TOPIC, greeting)) { meta, ex -> }
    future.get()

    then:
    thrown java.util.concurrent.ExecutionException

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

    def consumerProperties = KafkaTestUtils.consumerProps("sender", "false", embeddedKafka)
    def consumerFactory = new DefaultKafkaConsumerFactory<String, String>(consumerProperties)
    def containerProperties = containerProperties()
    def container = new KafkaMessageListenerContainer<>(consumerFactory, containerProperties)
    def records = new LinkedBlockingQueue<ConsumerRecord<String, String>>()

    container.setupMessageListener(new MessageListener<String, String>() {
        @Override
        void onMessage(ConsumerRecord<String, String> record) {
          records.add(record)
        }
      })
    container.start()
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

    cleanup:
    producerFactory.stop()
    container?.stop()
  }

  def "test pass through tombstone"() {
    setup:
    def senderProps = KafkaTestUtils.senderProps(embeddedKafka.getBrokersAsString())
    def producerFactory = new DefaultKafkaProducerFactory<String, String>(senderProps)
    def kafkaTemplate = new KafkaTemplate<String, String>(producerFactory)

    def consumerProperties = KafkaTestUtils.consumerProps("sender", "false", embeddedKafka)
    def consumerFactory = new DefaultKafkaConsumerFactory<String, String>(consumerProperties)
    def containerProperties = containerProperties()
    def container = new KafkaMessageListenerContainer<>(consumerFactory, containerProperties)
    def records = new LinkedBlockingQueue<ConsumerRecord<String, String>>()

    container.setupMessageListener(new MessageListener<String, String>() {
        @Override
        void onMessage(ConsumerRecord<String, String> record) {
          records.add(record)
        }
      })
    container.start()
    ContainerTestUtils.waitForAssignment(container, embeddedKafka.getPartitionsPerTopic())

    when:
    kafkaTemplate.send(SHARED_TOPIC, null)

    then:
    def received = records.poll(5, TimeUnit.SECONDS)
    received.value() == null

    assertTraces(2, SORT_TRACES_BY_ID) {
      trace(1) {
        producerSpan(it, senderProps, null, false, true)
      }
      if (hasQueueSpan()) {
        trace(2) {
          consumerSpan(it, consumerProperties, span(1), 0..0, true)
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

  def "test kafka consume with existing headers does not overwrite"() {
    setup:
    def senderProps = KafkaTestUtils.senderProps(embeddedKafka.getBrokersAsString())
    KafkaProducer<String, String> producer = new KafkaProducer<>(senderProps, new StringSerializer(), new StringSerializer())

    def consumerProperties = KafkaTestUtils.consumerProps("sender", "false", embeddedKafka)
    def consumerFactory = new DefaultKafkaConsumerFactory<String, String>(consumerProperties)
    def containerProperties = containerProperties()
    def container = new KafkaMessageListenerContainer<>(consumerFactory, containerProperties)
    def records = new LinkedBlockingQueue<ConsumerRecord<String, String>>()

    container.setupMessageListener(new MessageListener<String, String>() {
        @Override
        void onMessage(ConsumerRecord<String, String> record) {
          records.add(record)
        }
      })
    container.start()
    ContainerTestUtils.waitForAssignment(container, embeddedKafka.getPartitionsPerTopic())

    when:
    def producerRecord = new ProducerRecord<String, String>(SHARED_TOPIC, "test-with-headers")
    producerRecord.headers().add("x-datadog-trace-id", "1".getBytes())
    producerRecord.headers().add("x-datadog-parent-id", "1".getBytes())
    runUnderTrace("parent") {
      producer.send(producerRecord) { meta, ex -> }
      blockUntilChildSpansFinished(1)
    }

    then:
    def received = records.poll(5, TimeUnit.SECONDS)
    received.value() == "test-with-headers"
    // The instrumentation should have replaced the existing headers with new trace context
    def traceIdHeader = received.headers().headers("x-datadog-trace-id").iterator().next()
    new String(traceIdHeader.value()) != "1"

    cleanup:
    producer.close()
    container?.stop()
  }

  def producerSpan(
    TraceAssert trace,
    Map<String, ?> config,
    DDSpan parentSpan = null,
    boolean partitioned = true,
    boolean tombstone = false
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
        "$InstrumentationTags.MESSAGING_SYSTEM" "kafka"
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
      tags {
        "$Tags.COMPONENT" "java-kafka"
        "$Tags.SPAN_KIND" Tags.SPAN_KIND_CONSUMER
        "$InstrumentationTags.MESSAGING_SYSTEM" "kafka"
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
        defaultTags(distributedRootSpan)
      }
    }
  }

  def pollSpan(
    TraceAssert trace,
    int recordCount = 1,
    DDSpan parentSpan = null
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

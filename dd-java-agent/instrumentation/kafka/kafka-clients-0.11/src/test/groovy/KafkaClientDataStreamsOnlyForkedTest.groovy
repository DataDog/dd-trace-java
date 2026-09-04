import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.common.writer.ListWriter
import datadog.trace.core.DDSpan
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.header.internals.RecordHeader
import org.apache.kafka.common.header.internals.RecordHeaders
import org.apache.kafka.common.serialization.StringSerializer
import org.springframework.kafka.test.rule.KafkaEmbedded
import org.springframework.kafka.test.utils.KafkaTestUtils

import java.nio.charset.StandardCharsets

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

/**
 * DSM-only coverage for kafka-clients-0.11: when Kafka APM tracing is disabled
 * (integrations.enabled=false, or the per-integration trace.kafka.enabled=false override) but DSM
 * is enabled, this integration must never create or write a real span/trace, while pathway
 * checkpoints must still be tracked. These specs deliberately extend
 * {@code InstrumentationSpecification} directly rather than {@code KafkaClientTestBase}: that base
 * carries many concrete tests which assert on APM spans that are correctly absent once Kafka
 * tracing is off, and Spock would run them (and fail) as inherited tests on every subclass.
 */
abstract class KafkaClientDataStreamsOnlyForkedTest extends InstrumentationSpecification {
  static final SHARED_TOPIC = "shared.topic"

  KafkaEmbedded embeddedKafka

  def setup() {
    embeddedKafka = new KafkaEmbedded(1, true, SHARED_TOPIC)
    embeddedKafka.before()
  }

  def cleanup() {
    embeddedKafka?.after()
  }

  @Override
  boolean useStrictTraceWrites() {
    return false
  }

  @Override
  protected boolean isDataStreamsEnabled() {
    return true
  }

  protected KafkaProducer<String, String> newProducer() {
    return new KafkaProducer<String, String>(
      KafkaTestUtils.senderProps(embeddedKafka.getBrokersAsString()),
      new StringSerializer(),
      new StringSerializer())
  }
}

/**
 * Regression guard for the contract that "integration disabled => zero spans of that type ever
 * reach the agent": producing and consuming a message in DSM-only mode must not write any trace,
 * even though DSM checkpoints for the same produce/consume are still tracked.
 */
class KafkaClientDataStreamsOnlyLocalRootForkedTest extends KafkaClientDataStreamsOnlyForkedTest {
  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig("integrations.enabled", "false")
  }

  def "local-root produce and consume write no spans when kafka tracing is disabled and DSM is enabled"() {
    setup:
    def kafkaPartition = 0
    def consumerProperties = KafkaTestUtils.consumerProps("sender", "false", embeddedKafka)
    consumerProperties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
    def consumer = new KafkaConsumer<String, String>(consumerProperties)
    def producer = newProducer()
    consumer.assign(Arrays.asList(new TopicPartition(SHARED_TOPIC, kafkaPartition)))

    when: "a message is produced with no propagated trace headers, i.e. a genuine local root"
    producer.send(new ProducerRecord(SHARED_TOPIC, kafkaPartition, null, "local-root-message")).get()
    def recs = KafkaTestUtils.getRecords(consumer)
      .records(new TopicPartition(SHARED_TOPIC, kafkaPartition)).iterator()

    then: "the message is delivered and DSM checkpoints are tracked, but no span is ever written"
    recs.hasNext()
    recs.next().value() == "local-root-message"
    !recs.hasNext()
    TEST_DATA_STREAMS_WRITER.waitForGroups(2)
    TEST_WRITER.isEmpty()

    cleanup:
    consumer?.close()
    producer?.close()
  }
}

/**
 * Regression guard: a message carrying a real, externally-propagated Datadog trace context must
 * also write no span, under the same "kafka tracing disabled + DSM enabled" configuration - the
 * DSM-only decision is based purely on the tracing/DSM config flags, not on the record's headers.
 */
class KafkaClientDataStreamsOnlyExtractedParentForkedTest extends KafkaClientDataStreamsOnlyForkedTest {
  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig("integrations.enabled", "false")
  }

  def "produce and consume with an extracted parent trace context still write no spans"() {
    setup:
    def kafkaPartition = 0
    def consumerProperties = KafkaTestUtils.consumerProps("sender", "false", embeddedKafka)
    consumerProperties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
    def consumer = new KafkaConsumer<String, String>(consumerProperties)
    def producer = newProducer()
    consumer.assign(Arrays.asList(new TopicPartition(SHARED_TOPIC, kafkaPartition)))

    def existingTraceId = 1234567890123456L
    def existingSpanId = 9876543210987654L
    def headers = new RecordHeaders()
    headers.add(new RecordHeader("x-datadog-trace-id",
      String.valueOf(existingTraceId).getBytes(StandardCharsets.UTF_8)))
    headers.add(new RecordHeader("x-datadog-parent-id",
      String.valueOf(existingSpanId).getBytes(StandardCharsets.UTF_8)))

    when: "a message carrying a real, externally-propagated Datadog trace context is produced"
    producer.send(new ProducerRecord(SHARED_TOPIC, kafkaPartition, null, "propagated-trace-message", headers)).get()
    def recs = KafkaTestUtils.getRecords(consumer)
      .records(new TopicPartition(SHARED_TOPIC, kafkaPartition)).iterator()

    then: "the message is delivered and DSM checkpoints are tracked, but no span is ever written"
    recs.hasNext()
    recs.next().value() == "propagated-trace-message"
    !recs.hasNext()
    TEST_DATA_STREAMS_WRITER.waitForGroups(2)
    TEST_WRITER.isEmpty()

    cleanup:
    consumer?.close()
    producer?.close()
  }
}

/**
 * Confirms the per-integration override (trace.kafka.enabled=false) suppresses span creation
 * identically to the global integrations.enabled=false toggle used above.
 */
class KafkaClientDataStreamsOnlyIntegrationOverrideForkedTest extends KafkaClientDataStreamsOnlyForkedTest {
  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig("trace.kafka.enabled", "false")
  }

  def "local-root produce and consume write no spans when trace.kafka.enabled=false and DSM is enabled"() {
    setup:
    def kafkaPartition = 0
    def consumerProperties = KafkaTestUtils.consumerProps("sender", "false", embeddedKafka)
    consumerProperties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
    def consumer = new KafkaConsumer<String, String>(consumerProperties)
    def producer = newProducer()
    consumer.assign(Arrays.asList(new TopicPartition(SHARED_TOPIC, kafkaPartition)))

    when: "a message is produced with no propagated trace headers, i.e. a genuine local root"
    producer.send(new ProducerRecord(SHARED_TOPIC, kafkaPartition, null, "local-root-message")).get()
    def recs = KafkaTestUtils.getRecords(consumer)
      .records(new TopicPartition(SHARED_TOPIC, kafkaPartition)).iterator()

    then: "the message is delivered and DSM checkpoints are tracked, but no span is ever written"
    recs.hasNext()
    recs.next().value() == "local-root-message"
    !recs.hasNext()
    TEST_DATA_STREAMS_WRITER.waitForGroups(2)
    TEST_WRITER.isEmpty()

    cleanup:
    consumer?.close()
    producer?.close()
  }
}

/**
 * Regression guard: producing from inside an already active local trace in DSM-only mode must not
 * create any additional span either - the surrounding customer trace is untouched.
 */
class KafkaClientDataStreamsOnlyActiveLocalTraceForkedTest extends KafkaClientDataStreamsOnlyForkedTest {
  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig("integrations.enabled", "false")
  }

  def "producing inside an active local trace adds no span to that trace"() {
    setup:
    def producer = newProducer()

    when: "a message is produced from within an already active local trace"
    runUnderTrace("parent") {
      producer.send(new ProducerRecord(SHARED_TOPIC, 0, null, "in-active-trace")).get()
    }

    then: "the surrounding customer trace contains only its own span, no kafka.produce span"
    TEST_WRITER.waitForTraces(1)
    TEST_WRITER[0].size() == 1
    TEST_WRITER[0][0].operationName.toString() == "parent"

    cleanup:
    producer?.close()
  }
}

/**
 * Regression guard for the poll-span suppression site: KafkaConsumerInfoInstrumentation's
 * RecordsAdvice used to create a standalone "kafka.poll" span/trace around every consumer.poll()
 * call whenever DSM is enabled, regardless of whether Kafka APM tracing itself was enabled. In
 * DSM-only mode that span must no longer be created at all.
 */
class KafkaClientDataStreamsOnlyPollSpanForkedTest extends KafkaClientDataStreamsOnlyForkedTest {
  static final ListWriter.Filter ACCEPT_ALL = new ListWriter.Filter() {
    @Override
    boolean accept(List<DDSpan> trace) {
      return true
    }
  }

  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig("integrations.enabled", "false")
  }

  def "no poll span is created when kafka tracing is disabled and DSM is enabled"() {
    setup:
    // Undo any default poll-trace filter: this test needs to see the "kafka.poll" trace, if one
    // were (incorrectly) written.
    TEST_WRITER.setFilter(ACCEPT_ALL)
    def consumerProperties = KafkaTestUtils.consumerProps("sender", "false", embeddedKafka)
    consumerProperties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
    def consumer = new KafkaConsumer<String, String>(consumerProperties)
    consumer.assign(Arrays.asList(new TopicPartition(SHARED_TOPIC, 0)))

    when: "the consumer polls with no active local trace and no records to consume"
    KafkaTestUtils.getRecords(consumer)

    then: "no standalone kafka.poll trace was written"
    TEST_WRITER.isEmpty()

    cleanup:
    consumer?.close()
  }
}

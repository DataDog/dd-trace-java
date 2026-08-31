import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.sampling.PrioritySampling
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.StringSerializer
import org.springframework.kafka.test.EmbeddedKafkaBroker
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker
import org.springframework.kafka.test.utils.KafkaTestUtils

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

/**
 * DSM billing-suppression coverage for kafka-clients-3.8, mirroring the kafka-clients-0.11 suite.
 *
 * <p>Both scenarios run under "kafka tracing disabled (integrations.enabled=false) + DSM enabled",
 * the configuration in which the suppression guard is active. These specs deliberately do not
 * extend {@code KafkaClientTestBase}: that base asserts Code Origin tags, which are correctly
 * absent once Kafka tracing is off.
 */
abstract class KafkaClientDataStreamsOnlyForkedTest extends InstrumentationSpecification {
  static final SHARED_TOPIC = "shared.topic"

  EmbeddedKafkaBroker embeddedKafka

  def setup() {
    embeddedKafka = new EmbeddedKafkaKraftBroker(1, 2, SHARED_TOPIC)
    embeddedKafka.afterPropertiesSet()
  }

  def cleanup() {
    embeddedKafka.destroy()
  }

  @Override
  boolean useStrictTraceWrites() {
    return false
  }

  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig("integrations.enabled", "false")
    injectSysConfig("data.streams.enabled", "true")
  }

  /**
   * Locates a written span by operation name. The consumer's kafka.poll spans interleave
   * unpredictably with the produce/consume traces, so indexing into TEST_WRITER is unreliable.
   */
  protected findSpan(String operationName) {
    for (int i = 0; i < 100; i++) {
      def span = TEST_WRITER.flatten().find { it.operationName.toString() == operationName }
      if (span != null) {
        return span
      }
      Thread.sleep(100)
    }
    return null
  }

  protected KafkaProducer<String, String> newProducer() {
    return new KafkaProducer<String, String>(
      KafkaTestUtils.producerProps(embeddedKafka.getBrokersAsString()),
      new StringSerializer(),
      new StringSerializer())
  }
}

/**
 * A genuinely local-root produce/consume span must have its sampling priority forced to USER_DROP
 * so it does not count towards APM billing.
 */
class KafkaClientDataStreamsOnlyLocalRootForkedTest extends KafkaClientDataStreamsOnlyForkedTest {

  def "local-root produce and consume spans are forced to USER_DROP"() {
    setup:
    def kafkaPartition = 0
    def consumerProperties = KafkaTestUtils.consumerProps("sender", "false", embeddedKafka)
    consumerProperties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
    def consumer = new KafkaConsumer<String, String>(consumerProperties)
    def producer = newProducer()
    consumer.assign(Arrays.asList(new TopicPartition(SHARED_TOPIC, kafkaPartition)))

    when: "a message is produced with no propagated trace headers, i.e. a genuine local root"
    producer.send(new ProducerRecord(SHARED_TOPIC, kafkaPartition, null, "local-root-message")).get()

    then: "the produce span's trace is forced to USER_DROP to suppress APM billing"
    TEST_WRITER.waitForTraces(1)
    def produceSpan = findSpan("kafka.produce")
    produceSpan != null
    produceSpan.getSamplingPriority() == PrioritySampling.USER_DROP

    when: "the message is consumed"
    def recs = KafkaTestUtils.getRecords(consumer)
      .records(new TopicPartition(SHARED_TOPIC, kafkaPartition)).iterator()

    then: "the consume span's trace is also forced to USER_DROP"
    recs.hasNext()
    recs.next().value() == "local-root-message"
    !recs.hasNext()
    TEST_WRITER.waitForTraces(2)
    def consumeSpan = findSpan("kafka.consume")
    consumeSpan != null
    consumeSpan.getSamplingPriority() == PrioritySampling.USER_DROP

    cleanup:
    consumer?.close()
    producer?.close()
  }
}

/**
 * Regression guard for the producer suppression site: producing a message from inside an already
 * active local trace must NOT force that customer trace to USER_DROP. The produce span is created
 * with a scope-honouring startSpan overload, so it becomes a child of the active span, and
 * setSamplingPriority is trace-level.
 */
class KafkaClientDataStreamsOnlyActiveLocalTraceForkedTest extends KafkaClientDataStreamsOnlyForkedTest {

  def "producing inside an active local trace does not force that trace to USER_DROP"() {
    setup:
    def producer = newProducer()

    when: "a message is produced from within an already active local trace"
    runUnderTrace("parent") {
      producer.send(new ProducerRecord(SHARED_TOPIC, 0, null, "in-active-trace")).get()
    }

    then: "the surrounding customer trace is not force-dropped"
    TEST_WRITER.waitForTraces(1)
    def localRoot = TEST_WRITER[0][0].localRootSpan
    localRoot.operationName.toString() == "parent"
    localRoot.getSamplingPriority() != PrioritySampling.USER_DROP

    cleanup:
    producer?.close()
  }
}

/**
 * Regression guard for the poll-span suppression site: KafkaConsumerInfoInstrumentation's
 * RecordsAdvice creates a standalone "kafka.poll" span/trace around every consumer.poll() call
 * whenever DSM is enabled, regardless of whether Kafka APM tracing itself is enabled.
 */
class KafkaClientDataStreamsOnlyPollSpanForkedTest extends KafkaClientDataStreamsOnlyForkedTest {

  def "poll span is forced to USER_DROP"() {
    setup:
    def consumerProperties = KafkaTestUtils.consumerProps("sender", "false", embeddedKafka)
    consumerProperties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
    def consumer = new KafkaConsumer<String, String>(consumerProperties)
    consumer.assign(Arrays.asList(new TopicPartition(SHARED_TOPIC, 0)))

    when: "the consumer polls with no active local trace and no records to consume"
    KafkaTestUtils.getRecords(consumer)

    then: "the standalone kafka.poll trace is forced to USER_DROP, so it is not billed as APM"
    def pollSpan = findSpan("kafka.poll")
    pollSpan != null
    pollSpan.getSamplingPriority() == PrioritySampling.USER_DROP

    cleanup:
    consumer?.close()
  }
}

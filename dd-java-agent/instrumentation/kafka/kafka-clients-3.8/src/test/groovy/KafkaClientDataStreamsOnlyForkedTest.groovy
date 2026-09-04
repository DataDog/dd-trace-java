import datadog.trace.agent.test.InstrumentationSpecification
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
 * DSM-only coverage for kafka-clients-3.8: when Kafka APM tracing is disabled
 * (integrations.enabled=false) but DSM is enabled, this integration must never create or write a
 * real span/trace, while pathway checkpoints must still be tracked. These specs deliberately do
 * not extend {@code KafkaClientTestBase}: that base asserts on APM spans, which are correctly
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
  protected boolean isDataStreamsEnabled() {
    return true
  }

  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig("integrations.enabled", "false")
  }

  protected KafkaProducer<String, String> newProducer() {
    return new KafkaProducer<String, String>(
      KafkaTestUtils.producerProps(embeddedKafka.getBrokersAsString()),
      new StringSerializer(),
      new StringSerializer())
  }
}

/**
 * Regression guard for the contract that "integration disabled => zero spans of that type ever
 * reach the agent": producing and consuming a message in DSM-only mode must not write any trace,
 * even though DSM checkpoints for the same produce/consume are still tracked.
 */
class KafkaClientDataStreamsOnlyNoSpansForkedTest extends KafkaClientDataStreamsOnlyForkedTest {

  def "produce and consume in DSM-only mode write no spans, but still track DSM checkpoints"() {
    setup:
    def kafkaPartition = 0
    def consumerProperties = KafkaTestUtils.consumerProps("sender", "false", embeddedKafka)
    consumerProperties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
    def consumer = new KafkaConsumer<String, String>(consumerProperties)
    def producer = newProducer()
    consumer.assign(Arrays.asList(new TopicPartition(SHARED_TOPIC, kafkaPartition)))

    when: "a message is produced and consumed with no active trace"
    producer.send(new ProducerRecord(SHARED_TOPIC, kafkaPartition, null, "dsm-only-message")).get()
    def recs = KafkaTestUtils.getRecords(consumer)
      .records(new TopicPartition(SHARED_TOPIC, kafkaPartition)).iterator()

    then: "the message is delivered and DSM checkpoints are tracked for both hops"
    recs.hasNext()
    recs.next().value() == "dsm-only-message"
    !recs.hasNext()
    TEST_DATA_STREAMS_WRITER.waitForGroups(2, 15000)

    and: "no span was ever created or written for this integration"
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

  def "producing inside an active local trace in DSM-only mode adds no span to that trace"() {
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

  def "no poll span is created in DSM-only mode"() {
    setup:
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

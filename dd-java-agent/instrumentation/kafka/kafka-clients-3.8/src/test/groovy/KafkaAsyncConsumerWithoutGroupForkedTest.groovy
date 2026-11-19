import datadog.trace.agent.test.naming.VersionedNamingTestBase
import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.core.DDSpan
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker
import org.springframework.kafka.test.utils.KafkaTestUtils
import spock.lang.Shared

import java.time.Duration

/**
 * An AsyncKafkaConsumer (group.protocol=consumer) without a group.id and with Data Streams off
 * goes through ConstructorAdvice, not LegacyConstructorAdvice, so this checks that its spans still
 * get the bootstrap servers and the cluster ID.
 */
class KafkaAsyncConsumerWithoutGroupForkedTest extends VersionedNamingTestBase {
  static final String TOPIC = "async-consumer-without-group"
  static final TopicPartition PARTITION = new TopicPartition(TOPIC, 0)
  static final Duration TIMEOUT = Duration.ofSeconds(15)

  @Shared
  EmbeddedKafkaKraftBroker broker

  def setupSpec() {
    broker = new EmbeddedKafkaKraftBroker(1, 1, TOPIC)
    broker.brokerProperty("group.coordinator.rebalance.protocols", "classic,consumer")
    broker.brokerProperty("group.coordinator.new.enable", "true")
    broker.afterPropertiesSet()
  }

  def cleanupSpec() {
    broker?.destroy()
  }

  @Override
  int version() {
    0
  }

  @Override
  String operation() {
    null
  }

  @Override
  String service() {
    "kafka"
  }

  @Override
  protected boolean isDataStreamsEnabled() {
    false
  }

  def "async consumer without a group tags bootstrap servers and the cluster id"() {
    setup:
    def properties = KafkaTestUtils.consumerProps("unused", "false", broker)
    properties.remove(ConsumerConfig.GROUP_ID_CONFIG)
    properties.remove(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG)
    properties.remove(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG)
    properties.put(ConsumerConfig.GROUP_PROTOCOL_CONFIG, "consumer")
    properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
    properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer)
    properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer)
    def consumer = new KafkaConsumer<String, String>(properties)
    assert consumer.@delegate.class.simpleName == "AsyncKafkaConsumer"
    consumer.assign([PARTITION])

    def producer = new KafkaProducer<String, String>(
      KafkaTestUtils.producerProps(broker.getBrokersAsString()), new StringSerializer(), new StringSerializer())

    when:
    producer.send(new ProducerRecord<String, String>(TOPIC, 0, null, "Hello without a group!")).get()
    def received = []
    long deadline = System.nanoTime() + TIMEOUT.toNanos()
    while (received.empty && System.nanoTime() < deadline) {
      for (def record : consumer.poll(Duration.ofMillis(100))) {
        received << record.value()
      }
    }
    def consumeSpan = awaitSpan(Tags.SPAN_KIND_CONSUMER)
    def produceSpan = awaitSpan(Tags.SPAN_KIND_PRODUCER)

    then:
    received == ["Hello without a group!"]
    consumeSpan.getTag(InstrumentationTags.KAFKA_BOOTSTRAP_SERVERS) == broker.getBrokersAsString()
    consumeSpan.getTag(InstrumentationTags.KAFKA_CLUSTER_ID) instanceof String
    !(consumeSpan.getTag(InstrumentationTags.KAFKA_CLUSTER_ID) as String).isEmpty()
    consumeSpan.getTag(InstrumentationTags.CONSUMER_GROUP) == null
    produceSpan.getTag(InstrumentationTags.KAFKA_CLUSTER_ID) == consumeSpan.getTag(InstrumentationTags.KAFKA_CLUSTER_ID)

    cleanup:
    consumer?.close(TIMEOUT)
    producer?.close()
  }

  private DDSpan awaitSpan(String kind) {
    long deadline = System.nanoTime() + TIMEOUT.toNanos()
    while (System.nanoTime() < deadline) {
      def span = TEST_WRITER.flatten().find { it.getTag(Tags.SPAN_KIND) == kind }
      if (span != null) {
        return span
      }
      Thread.sleep(20)
    }
    throw new AssertionError("No $kind span reported")
  }
}

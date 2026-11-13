import static datadog.trace.api.datastreams.PathwayContext.PROPAGATION_KEY_BASE64

import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.DynamicConfig
import datadog.trace.api.datastreams.NoopPathwayContext
import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags
import datadog.trace.core.CoreTracer
import datadog.trace.core.DDSpan
import java.lang.reflect.Field
import java.time.Duration
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker
import org.springframework.kafka.test.utils.KafkaTestUtils
import spock.lang.Shared
import spock.util.concurrent.PollingConditions

/** Data Streams off at startup, then toggled at runtime the way remote config does. */
class KafkaDataStreamsRemoteConfigForkedTest extends InstrumentationSpecification {
  static final String TOPIC = "dsm.remote.config"
  static final TopicPartition PARTITION = new TopicPartition(TOPIC, 0)

  @Shared
  EmbeddedKafkaKraftBroker broker

  @Shared
  volatile boolean dataStreamsOn = false

  def setupSpec() {
    broker = new EmbeddedKafkaKraftBroker(1, 1, TOPIC)
    broker.brokerProperty("group.coordinator.rebalance.protocols", "classic,consumer")
    broker.brokerProperty("group.coordinator.new.enable", "true")
    broker.afterPropertiesSet()
  }

  def cleanupSpec() {
    broker?.destroy()
  }

  def cleanup() {
    toggleDataStreams(false)
  }

  @Override
  protected boolean isDataStreamsEnabled() {
    return dataStreamsOn
  }

  def "data streams follows the live config for async consumers built while it was off"() {
    setup:
    def producer = newProducer()
    def consumer = newConsumer("consumer")
    assert consumer.@delegate.class.simpleName == "AsyncKafkaConsumer"

    when: "data streams is off"
    def offRecord = produceAndConsume(producer, consumer, "off")

    then:
    offRecord.headers().lastHeader(PROPAGATION_KEY_BASE64) == null
    consumeSpan().getTag(InstrumentationTags.KAFKA_CLUSTER_ID) == null

    when: "remote config enables data streams"
    toggleDataStreams(true)
    def onRecord = produceAndConsume(producer, consumer, "on")

    then:
    onRecord.headers().lastHeader(PROPAGATION_KEY_BASE64) != null
    consumeSpan().getTag(InstrumentationTags.KAFKA_CLUSTER_ID) != null
    new PollingConditions(timeout: 5).eventually {
      assert TEST_DATA_STREAMS_WRITER.groups.find { it.tags.hasAllTags("direction:out", "topic:$TOPIC".toString(), "type:kafka") }
      assert TEST_DATA_STREAMS_WRITER.groups.find { it.tags.hasAllTags("direction:in", "topic:$TOPIC".toString(), "type:kafka") }
    }

    when: "remote config disables data streams again"
    toggleDataStreams(false)
    def offAgainRecord = produceAndConsume(producer, consumer, "off again")

    then:
    offAgainRecord.headers().lastHeader(PROPAGATION_KEY_BASE64) == null
    consumeSpan().getTag(InstrumentationTags.KAFKA_CLUSTER_ID) == null

    cleanup:
    producer?.close()
    consumer?.close()
  }

  def "classic consumers keep the cluster id tag with data streams off"() {
    setup:
    def producer = newProducer()
    def consumer = newConsumer("classic")
    assert consumer.@delegate.class.simpleName == "LegacyKafkaConsumer"

    when:
    def record = produceAndConsume(producer, consumer, "classic")

    then:
    record.headers().lastHeader(PROPAGATION_KEY_BASE64) == null
    consumeSpan().getTag(InstrumentationTags.KAFKA_CLUSTER_ID) != null

    cleanup:
    producer?.close()
    consumer?.close()
  }

  KafkaProducer<String, String> newProducer() {
    return new KafkaProducer<String, String>(KafkaTestUtils.producerProps(broker), new StringSerializer(), new StringSerializer())
  }

  KafkaConsumer<String, String> newConsumer(String groupProtocol) {
    def properties = KafkaTestUtils.consumerProps("group-$groupProtocol".toString(), "false", broker)
    properties.remove(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG)
    properties.remove(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG)
    properties.put(ConsumerConfig.GROUP_PROTOCOL_CONFIG, groupProtocol)
    def consumer = new KafkaConsumer<String, String>(properties, new StringDeserializer(), new StringDeserializer())
    consumer.assign([PARTITION])
    consumer.seekToEnd([PARTITION])
    consumer.position(PARTITION)
    return consumer
  }

  /** Updates the tracer's dynamic config like remote config, then waits for the DSM monitor. */
  void toggleDataStreams(boolean enabled) {
    dataStreamsOn = enabled
    Field dynamicConfig = CoreTracer.getDeclaredField("dynamicConfig")
    dynamicConfig.setAccessible(true)
    (dynamicConfig.get(TEST_TRACER) as DynamicConfig).current().setDataStreamsEnabled(enabled).apply()
    new PollingConditions(timeout: 5).eventually {
      assert (TEST_DATA_STREAMS_MONITORING.newPathwayContext() instanceof NoopPathwayContext) != enabled
    }
  }

  ConsumerRecord<String, String> produceAndConsume(
    KafkaProducer<String, String> producer, KafkaConsumer<String, String> consumer, String value) {
    TEST_WRITER.clear()
    producer.send(new ProducerRecord<String, String>(TOPIC, 0, null, value)).get()
    ConsumerRecord<String, String> consumed = null
    long deadline = System.currentTimeMillis() + 10_000
    while (consumed == null && System.currentTimeMillis() < deadline) {
      // iterating to the end finishes the consume span
      for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(100))) {
        if (record.value() == value) {
          consumed = record
        }
      }
    }
    assert consumed != null
    return consumed
  }

  /** The single consume span written since the last {@link #produceAndConsume}. */
  DDSpan consumeSpan() {
    List<DDSpan> spans = []
    long deadline = System.currentTimeMillis() + 5_000
    while (spans.isEmpty() && System.currentTimeMillis() < deadline) {
      spans = TEST_WRITER.flatten().findAll { DDSpan span -> span.operationName.toString() == "kafka.consume" }
      Thread.sleep(20)
    }
    assert spans.size() == 1
    return spans[0]
  }
}

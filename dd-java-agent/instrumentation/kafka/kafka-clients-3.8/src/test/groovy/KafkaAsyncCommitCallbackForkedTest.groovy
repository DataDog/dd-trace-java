import datadog.trace.agent.test.naming.VersionedNamingTestBase
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.consumer.OffsetAndMetadata
import org.apache.kafka.clients.consumer.OffsetCommitCallback
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.StringDeserializer
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker
import org.springframework.kafka.test.utils.KafkaTestUtils
import spock.lang.Shared
import spock.util.concurrent.PollingConditions

import java.time.Duration

class KafkaAsyncCommitCallbackForkedTest extends VersionedNamingTestBase {
  static final String TOPIC = "async-commit-callback"
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
    true
  }

  def "async commit preserves callback and reports backlog during #completion"() {
    setup:
    def group = "async-${UUID.randomUUID()}"
    def properties = KafkaTestUtils.consumerProps(group, "false", broker)
    properties.remove(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG)
    properties.remove(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG)
    properties.put(ConsumerConfig.GROUP_PROTOCOL_CONFIG, "consumer")
    properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer)
    properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer)
    def consumer = new KafkaConsumer<String, String>(properties)
    assert consumer.@delegate.class.simpleName == "AsyncKafkaConsumer"
    consumer.assign([PARTITION])
    consumer.seek(PARTITION, 0L)
    // Resolve the coordinator before the one-shot asynchronous commit under test.
    consumer.committed(Collections.singleton(PARTITION), TIMEOUT)
    def callbacks = []
    def applicationThread = Thread.currentThread()
    def offsets = [(PARTITION): new OffsetAndMetadata(2L)]

    when:
    consumer.commitAsync(offsets, { Map committed, Exception error ->
      callbacks.add([committed, error, Thread.currentThread()])
    } as OffsetCommitCallback)
    if (completion == "close") {
      consumer.close(TIMEOUT)
    } else if (completion == "sync commit") {
      consumer.commitSync(Collections.emptyMap(), TIMEOUT)
    } else {
      long deadline = System.nanoTime() + TIMEOUT.toNanos()
      while (callbacks.empty && System.nanoTime() < deadline) {
        consumer.poll(Duration.ofMillis(100))
      }
    }

    then:
    callbacks.size() == 1
    callbacks[0][0] == offsets
    callbacks[0][1] == null
    callbacks[0][2].is(applicationThread)
    new PollingConditions(timeout: 15).eventually {
      assert TEST_DATA_STREAMS_WRITER.payloads.any { bucket ->
        bucket.backlogs.any { entry ->
          entry.value == 2L && entry.key.kafkaClusterId &&
            entry.key.hasAllTags("type:kafka_commit", "consumer_group:$group".toString(),
            "topic:$TOPIC".toString(), "partition:0")
        }
      }
    }

    cleanup:
    consumer?.close(TIMEOUT)

    where:
    completion << ["poll", "sync commit", "close"]
  }
}

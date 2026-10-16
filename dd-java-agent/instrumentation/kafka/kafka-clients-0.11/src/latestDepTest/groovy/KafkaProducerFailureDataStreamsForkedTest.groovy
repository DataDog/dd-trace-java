import datadog.trace.agent.test.naming.VersionedNamingTestBase
import datadog.trace.api.Config
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.kafka.common.serialization.StringSerializer
import org.springframework.kafka.test.EmbeddedKafkaBroker
import org.springframework.kafka.test.utils.KafkaTestUtils
import spock.util.concurrent.PollingConditions

import java.util.concurrent.ExecutionException

class KafkaProducerFailureDataStreamsForkedTest extends VersionedNamingTestBase {
  static final String OK_TOPIC = "ok.topic"
  static final String REJECTING_TOPIC = "rejecting.topic"

  EmbeddedKafkaBroker embeddedKafka

  def setup() {
    embeddedKafka = new EmbeddedKafkaBroker(1, true, OK_TOPIC)
    embeddedKafka.afterPropertiesSet()
    embeddedKafka.addTopics(new NewTopic(REJECTING_TOPIC, 1, (short) 1).configs(["max.message.bytes": "512"]))
  }

  def cleanup() {
    embeddedKafka?.destroy()
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
    Config.get().getServiceName()
  }

  @Override
  protected boolean isDataStreamsEnabled() {
    true
  }

  def "produce rejected by the broker does not report a kafka_produce backlog"() {
    setup:
    def producer = newProducer([:])

    when:
    producer.send(new ProducerRecord<String, String>(REJECTING_TOPIC, "x" * 2048)).get()

    then:
    thrown(ExecutionException)

    when:
    RecordMetadata ok = producer.send(new ProducerRecord<String, String>(OK_TOPIC, "hello")).get()
    waitForProduceBacklog(OK_TOPIC)

    then:
    produceBacklogs() == ["topic:$OK_TOPIC partition:${ok.partition()} offset:${ok.offset()}".toString()]

    cleanup:
    producer?.close()
  }

  def "produce rejected by the client does not report a kafka_produce backlog"() {
    setup:
    def failingProducer = newProducer([(ProducerConfig.MAX_REQUEST_SIZE_CONFIG): 10])
    def producer = newProducer([:])

    when:
    failingProducer.send(new ProducerRecord<String, String>(REJECTING_TOPIC, "hello")).get()

    then:
    thrown(ExecutionException)

    when:
    RecordMetadata ok = producer.send(new ProducerRecord<String, String>(OK_TOPIC, "hello")).get()
    waitForProduceBacklog(OK_TOPIC)

    then:
    produceBacklogs() == ["topic:$OK_TOPIC partition:${ok.partition()} offset:${ok.offset()}".toString()]

    cleanup:
    failingProducer?.close()
    producer?.close()
  }

  KafkaProducer<String, String> newProducer(Map<String, Object> overrides) {
    def props = KafkaTestUtils.producerProps(embeddedKafka.getBrokersAsString())
    props.put(ProducerConfig.RETRIES_CONFIG, 0)
    props.putAll(overrides)
    new KafkaProducer<>(props, new StringSerializer(), new StringSerializer())
  }

  List<String> produceBacklogs() {
    TEST_DATA_STREAMS_WRITER.payloads
      .collectMany { it.backlogs ?: [] }
      .findAll { it.key.hasAllTags("type:kafka_produce") }
      .collect { "${it.key.topic} ${it.key.partition} offset:${it.value}".toString() }
      .sort()
  }

  // Backlogs recorded before this one sit in the same or an earlier bucket, so they are flushed by then.
  void waitForProduceBacklog(String topic) {
    new PollingConditions(timeout: 10).eventually {
      assert produceBacklogs().any { it.startsWith("topic:$topic ") }
    }
  }
}

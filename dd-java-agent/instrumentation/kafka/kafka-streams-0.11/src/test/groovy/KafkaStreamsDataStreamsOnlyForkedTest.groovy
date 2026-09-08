import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.config.TraceInstrumentationConfig
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.header.internals.RecordHeader
import org.apache.kafka.common.header.internals.RecordHeaders
import org.apache.kafka.common.serialization.Serdes
import org.apache.kafka.common.serialization.StringSerializer
import org.apache.kafka.streams.KafkaStreams
import org.apache.kafka.streams.StreamsConfig
import org.apache.kafka.streams.kstream.KStream
import org.apache.kafka.streams.kstream.KStreamBuilder
import org.apache.kafka.streams.kstream.ValueMapper
import org.springframework.kafka.test.rule.KafkaEmbedded
import org.springframework.kafka.test.utils.KafkaTestUtils
import spock.lang.Shared

import java.nio.charset.StandardCharsets

/**
 * DSM-only coverage for the kafka-streams StreamTask consume path: when Kafka APM tracing is
 * disabled (integrations.enabled=false) but DSM is enabled, this integration must never create or
 * write a real span/trace, whether or not the record carries a propagated trace context.
 */
abstract class KafkaStreamsDataStreamsOnlyForkedTest extends InstrumentationSpecification {
  static final STREAM_PENDING = "test.pending"
  static final STREAM_PROCESSED = "test.processed"

  @Shared
  protected KafkaEmbedded embeddedKafka

  def setupSpec() {
    embeddedKafka = new KafkaEmbedded(1, true, 1, STREAM_PENDING, STREAM_PROCESSED)
    embeddedKafka.before()
  }

  def cleanupSpec() {
    embeddedKafka?.after()
  }

  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig("integrations.enabled", "false")
  }

  @Override
  boolean useStrictTraceWrites() {
    return false
  }

  @Override
  protected boolean isDataStreamsEnabled() {
    return true
  }

  protected KafkaStreams startLowercasingTopology() {
    def config = new Properties()
    config.putAll(KafkaTestUtils.senderProps(embeddedKafka.getBrokersAsString()))
    config.put(StreamsConfig.APPLICATION_ID_CONFIG, "dsm-only-test-application")
    config.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String().getClass().getName())
    config.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.String().getClass().getName())

    def builder = new KStreamBuilder()
    KStream<String, String> textLines = builder.stream(STREAM_PENDING)
    textLines
      .mapValues(new ValueMapper<String, String>() {
        @Override
        String apply(String textLine) {
          return textLine.toLowerCase()
        }
      })
      .to(Serdes.String(), Serdes.String(), STREAM_PROCESSED)

    def streams = new KafkaStreams(builder, config)
    streams.start()
    return streams
  }

  protected KafkaProducer<String, String> newProducer() {
    return new KafkaProducer<String, String>(
      KafkaTestUtils.senderProps(embeddedKafka.getBrokersAsString()),
      new StringSerializer(),
      new StringSerializer())
  }
}

/**
 * A record with no propagated Datadog trace context must not create any streams consume span in
 * DSM-only mode - only a DSM checkpoint, tracked via the lightweight pathway-only span shim.
 */
class KafkaStreamsDataStreamsOnlyLocalRootForkedTest extends KafkaStreamsDataStreamsOnlyForkedTest {

  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    // The in-JVM producer would otherwise inject its own trace context into the record headers,
    // which the streams ContextPropagationAdvice would then extract - so there would be no way to
    // exercise the genuinely-local-root path. Disabling client propagation for the topic stops
    // both the injection and the extraction.
    injectSysConfig(TraceInstrumentationConfig.KAFKA_CLIENT_PROPAGATION_DISABLED_TOPICS, STREAM_PENDING)
  }

  def "a local-root record does not create a streams consume span"() {
    setup:
    def streams = startLowercasingTopology()
    def producer = newProducer()

    when:
    producer.send(new ProducerRecord<String, String>(STREAM_PENDING, "LOCAL ROOT")).get()

    then:
    TEST_DATA_STREAMS_WRITER.waitForGroups(1)
    TEST_WRITER.isEmpty()

    cleanup:
    producer?.close()
    streams?.close()
  }
}

/**
 * Regression guard: a record carrying a real, externally-propagated Datadog trace context must
 * still not create any streams consume span in DSM-only mode.
 */
class KafkaStreamsDataStreamsOnlyExtractedParentForkedTest extends KafkaStreamsDataStreamsOnlyForkedTest {

  def "a record continuing a propagated trace does not create a streams consume span either"() {
    setup:
    def streams = startLowercasingTopology()
    def producer = newProducer()
    def existingTraceId = 1234567890123456L
    def existingSpanId = 9876543210987654L
    def headers = new RecordHeaders()
    headers.add(new RecordHeader("x-datadog-trace-id",
      String.valueOf(existingTraceId).getBytes(StandardCharsets.UTF_8)))
    headers.add(new RecordHeader("x-datadog-parent-id",
      String.valueOf(existingSpanId).getBytes(StandardCharsets.UTF_8)))

    when:
    producer.send(new ProducerRecord<String, String>(STREAM_PENDING, null, null, "PROPAGATED", headers)).get()

    then:
    TEST_DATA_STREAMS_WRITER.waitForGroups(1)
    TEST_WRITER.isEmpty()

    cleanup:
    producer?.close()
    streams?.close()
  }
}

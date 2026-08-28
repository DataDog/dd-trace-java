import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.config.TraceInstrumentationConfig
import datadog.trace.api.sampling.PrioritySampling
import datadog.trace.bootstrap.instrumentation.api.Tags
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
 * DSM billing-suppression coverage for the kafka-streams StreamTask consume spans.
 *
 * <p>Both scenarios run under "kafka tracing disabled (integrations.enabled=false) + DSM enabled",
 * the configuration in which the suppression guard is active.
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
    injectSysConfig("data.streams.enabled", "true")
  }

  @Override
  boolean useStrictTraceWrites() {
    return false
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

  /**
   * Polls the test writer until a kafka-streams consume span shows up, so the assertions do not
   * depend on how many other traces (produce, poll, downstream produce) are flushed first.
   */
  protected findStreamsConsumeSpan() {
    for (int i = 0; i < 100; i++) {
      def span = TEST_WRITER.flatten().find {
        it.operationName.toString() == "kafka.consume" &&
          it.getTag(Tags.COMPONENT)?.toString() == "java-kafka-streams"
      }
      if (span != null) {
        return span
      }
      Thread.sleep(100)
    }
    return null
  }
}

/**
 * A record with no propagated Datadog trace context produces a genuinely local-root streams
 * consume span, which must be forced to USER_DROP so it does not count towards APM billing.
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

  def "a local-root streams consume span is forced to USER_DROP"() {
    setup:
    def streams = startLowercasingTopology()
    def producer = newProducer()

    when:
    producer.send(new ProducerRecord<String, String>(STREAM_PENDING, "LOCAL ROOT")).get()

    then:
    def consumeSpan = findStreamsConsumeSpan()
    consumeSpan != null
    consumeSpan.getSamplingPriority() == PrioritySampling.USER_DROP

    cleanup:
    producer?.close()
    streams?.close()
  }
}

/**
 * Regression guard: a record carrying a real, externally-propagated Datadog trace context must NOT
 * have its trace force-dropped. The sibling ContextPropagationAdvice attaches that extracted
 * context to the scope before the span-starting advice runs, and setSamplingPriority is
 * trace-level, so without the guard the whole propagated trace would be silently dropped.
 */
class KafkaStreamsDataStreamsOnlyExtractedParentForkedTest extends KafkaStreamsDataStreamsOnlyForkedTest {

  def "a streams consume span continuing a propagated trace is not forced to USER_DROP"() {
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
    def consumeSpan = findStreamsConsumeSpan()
    consumeSpan != null
    consumeSpan.traceId.toLong() == existingTraceId
    consumeSpan.getSamplingPriority() != PrioritySampling.USER_DROP

    cleanup:
    producer?.close()
    streams?.close()
  }
}

import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.agent.test.checkpoints.CheckpointValidationMode
import datadog.trace.agent.test.checkpoints.CheckpointValidator
import datadog.trace.api.Platform
import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.core.datastreams.StatsGroup
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.serialization.Serdes
import org.apache.kafka.streams.KafkaStreams
import org.apache.kafka.streams.StreamsBuilder
import org.apache.kafka.streams.StreamsConfig
import org.apache.kafka.streams.kstream.KStream
import org.apache.kafka.streams.kstream.Produced
import org.apache.kafka.streams.kstream.ValueMapper
import org.junit.ClassRule
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.listener.ContainerProperties
import org.springframework.kafka.listener.KafkaMessageListenerContainer
import org.springframework.kafka.listener.MessageListener
import org.springframework.kafka.test.EmbeddedKafkaBroker
import org.springframework.kafka.test.rule.EmbeddedKafkaRule
import org.springframework.kafka.test.utils.ContainerTestUtils
import org.springframework.kafka.test.utils.KafkaTestUtils
import spock.lang.Shared

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class KafkaStreamsTest extends AgentTestRunner {
  static final STREAM_PENDING = "test.pending"
  static final STREAM_PROCESSED = "test.processed"

  @Shared
  @ClassRule
  EmbeddedKafkaRule kafkaRule = new EmbeddedKafkaRule(1, true, STREAM_PENDING, STREAM_PROCESSED)
  @Shared
  EmbeddedKafkaBroker embeddedKafka = kafkaRule.embeddedKafka

  @Override
  protected boolean isDataStreamsEnabled() {
    return true
  }

  def "test kafka produce and consume with streams in-between"() {
    setup:
    CheckpointValidator.excludeValidations_DONOTUSE_I_REPEAT_DO_NOT_USE(CheckpointValidationMode.INTERVALS)
    def config = new Properties()
    def producerProps = KafkaTestUtils.producerProps(embeddedKafka.getBrokersAsString())
    config.putAll(producerProps)
    config.put(StreamsConfig.APPLICATION_ID_CONFIG, "test-application")
    config.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String().getClass().getName())
    config.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.String().getClass().getName())

    // CONFIGURE CONSUMER
    def consumerFactory = new DefaultKafkaConsumerFactory<String, String>(KafkaTestUtils.consumerProps("sender", "false", embeddedKafka))

    def consumerContainer = new KafkaMessageListenerContainer<>(consumerFactory, new ContainerProperties(STREAM_PROCESSED))

    // create a thread safe queue to store the processed message
    def records = new LinkedBlockingQueue<ConsumerRecord<String, String>>()

    // setup a Kafka message listener
    consumerContainer.setupMessageListener(new MessageListener<String, String>() {
        @Override
        void onMessage(ConsumerRecord<String, String> record) {
          // ensure consistent ordering of traces
          // this is the last processing step so we should see 2 traces here
          TEST_WRITER.waitForTraces(2)
          TEST_TRACER.activeSpan().setTag("testing", 123)
          if (Platform.isJavaVersionAtLeast(8) && isDataStreamsEnabled()) {
            TEST_DATA_STREAMS_WRITER.waitForGroups(1)
          }
          records.add(record)
        }
      })

    // start the container and underlying message listener
    consumerContainer.start()

    // wait until the container has the required number of assigned partitions
    ContainerTestUtils.waitForAssignment(consumerContainer, embeddedKafka.getPartitionsPerTopic())

    // CONFIGURE PROCESSOR
    StreamsBuilder builder = new StreamsBuilder()
    KStream<String, String> textLines = builder.stream(STREAM_PENDING)
    def values = textLines
      .mapValues(new ValueMapper<String, String>() {
        @Override
        String apply(String textLine) {
          TEST_WRITER.waitForTraces(1) // ensure consistent ordering of traces
          TEST_TRACER.activeSpan().setTag("asdf", "testing")
          if (Platform.isJavaVersionAtLeast(8) && isDataStreamsEnabled()) {
            TEST_DATA_STREAMS_WRITER.waitForGroups(1)
          }
          return textLine.toLowerCase()
        }
      })

    def producer = Produced.with(Serdes.String(), Serdes.String())
    values.to(STREAM_PROCESSED, producer)
    KafkaStreams streams = new KafkaStreams(builder.build(), config)
    streams.start()

    // CONFIGURE PRODUCER
    def producerFactory = new DefaultKafkaProducerFactory<String, String>(producerProps)
    def kafkaTemplate = new KafkaTemplate<String, String>(producerFactory)

    when:
    String greeting = "TESTING TESTING 123!"
    kafkaTemplate.send(STREAM_PENDING, greeting)

    then:
    // check that the message was received
    def received = records.poll(10, TimeUnit.SECONDS)
    received.value() == greeting.toLowerCase()
    received.key() == null

    assertTraces(3) {
      trace(1) {
        // PRODUCER span 0
        span {
          serviceName "kafka"
          operationName "kafka.produce"
          resourceName "Produce Topic $STREAM_PENDING"
          spanType "queue"
          errored false
          measured true
          parent()
          tags {
            "$Tags.COMPONENT" "java-kafka"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_PRODUCER
            defaultTags()
          }
        }
      }
      def producerSpan = null
      trace(2) {
        sortSpansByStart()

        // STREAMING span 0
        span {
          serviceName "kafka"
          operationName "kafka.consume"
          resourceName "Consume Topic $STREAM_PENDING"
          spanType "queue"
          errored false
          measured true
          childOf trace(0)[0]

          tags {
            "$Tags.COMPONENT" "java-kafka-streams"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CONSUMER
            "$InstrumentationTags.PARTITION" { it >= 0 }
            "$InstrumentationTags.OFFSET" 0
            "$InstrumentationTags.PROCESSOR_NAME" "KSTREAM-SOURCE-0000000000"
            "asdf" "testing"
            defaultTags(true)
          }
        }

        // STREAMING span 1
        span {
          producerSpan = it.span
          serviceName "kafka"
          operationName "kafka.produce"
          resourceName "Produce Topic $STREAM_PROCESSED"
          spanType "queue"
          errored false
          measured true
          childOf span(0)

          tags {
            "$Tags.COMPONENT" "java-kafka"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_PRODUCER
            defaultTags()
          }
        }
      }
      trace(1) {
        // CONSUMER span 0
        span {
          serviceName "kafka"
          operationName "kafka.consume"
          resourceName "Consume Topic $STREAM_PROCESSED"
          spanType "queue"
          errored false
          measured true
          childOf producerSpan
          tags {
            "$Tags.COMPONENT" "java-kafka"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CONSUMER
            "$InstrumentationTags.PARTITION" { it >= 0 }
            "$InstrumentationTags.OFFSET" 0
            "$InstrumentationTags.CONSUMER_GROUP" "sender"
            "$InstrumentationTags.RECORD_QUEUE_TIME_MS" { it >= 0 }
            "testing" 123
            defaultTags(true)
          }
        }
      }
    }

    def headers = received.headers()
    headers.iterator().hasNext()
    new String(headers.headers("x-datadog-trace-id").iterator().next().value()) == "${TEST_WRITER[1][0].traceId}"
    new String(headers.headers("x-datadog-parent-id").iterator().next().value()) == "${TEST_WRITER[1][0].spanId}"

    if (Platform.isJavaVersionAtLeast(8) && isDataStreamsEnabled()) {
      StatsGroup originProducerPoint = TEST_DATA_STREAMS_WRITER.groups.find { it.parentHash == 0 }
      verifyAll(originProducerPoint) {
        edgeTags.containsAll(["type:internal"])
        edgeTags.size() == 1
      }

      StatsGroup kafkaStreamsConsumerPoint = TEST_DATA_STREAMS_WRITER.groups.find { it.parentHash == originProducerPoint.hash }
      verifyAll(kafkaStreamsConsumerPoint) {
        edgeTags.containsAll(["type:kafka", "group:test-application", "topic:$STREAM_PENDING".toString()])
        edgeTags.size() == 3
      }

      StatsGroup kafkaStreamsProducerPoint = TEST_DATA_STREAMS_WRITER.groups.find { it.parentHash == kafkaStreamsConsumerPoint.hash }
      verifyAll(kafkaStreamsProducerPoint) {
        edgeTags.containsAll(["type:internal"])
        edgeTags.size() == 1
      }

      StatsGroup finalConsumerPoint = TEST_DATA_STREAMS_WRITER.groups.find { it.parentHash == kafkaStreamsProducerPoint.hash }
      verifyAll(finalConsumerPoint) {
        edgeTags.containsAll(["type:kafka", "group:sender", "topic:$STREAM_PROCESSED".toString()])
        edgeTags.size() == 3
      }
    }

    cleanup:
    producerFactory?.destroy()
    streams?.close()
    consumerContainer?.stop()
  }
}

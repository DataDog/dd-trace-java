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
import org.apache.kafka.streams.StreamsConfig
import org.apache.kafka.streams.kstream.KStream
import org.apache.kafka.streams.kstream.KStreamBuilder
import org.apache.kafka.streams.kstream.ValueMapper
import org.junit.ClassRule
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.listener.KafkaMessageListenerContainer
import org.springframework.kafka.listener.MessageListener
import org.springframework.kafka.listener.config.ContainerProperties
import org.springframework.kafka.test.rule.KafkaEmbedded
import org.springframework.kafka.test.utils.ContainerTestUtils
import org.springframework.kafka.test.utils.KafkaTestUtils
import spock.lang.Shared

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

abstract class KafkaStreamsTestBase extends AgentTestRunner {
  static final STREAM_PENDING = "test.pending"
  static final STREAM_PROCESSED = "test.processed"

  @Shared
  @ClassRule
  KafkaEmbedded embeddedKafka = new KafkaEmbedded(1, true, STREAM_PENDING, STREAM_PROCESSED)

  abstract String expectedServiceName()

  abstract boolean hasQueueSpan()

  abstract boolean splitByDestination()

  @Override
  protected boolean isDataStreamsEnabled() {
    return true
  }

  def "test kafka produce and consume with streams in-between"() {
    setup:
    CheckpointValidator.excludeValidations_DONOTUSE_I_REPEAT_DO_NOT_USE(CheckpointValidationMode.INTERVALS)
    def config = new Properties()
    def senderProps = KafkaTestUtils.senderProps(embeddedKafka.getBrokersAsString())
    config.putAll(senderProps)
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
    def builder = new KStreamBuilder()
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

    values.to(Serdes.String(), Serdes.String(), STREAM_PROCESSED)
    KafkaStreams streams = new KafkaStreams(builder, config)
    streams.start()

    // CONFIGURE PRODUCER
    def producerFactory = new DefaultKafkaProducerFactory<String, String>(senderProps)
    def kafkaTemplate = new KafkaTemplate<String, String>(producerFactory)

    when:
    String greeting = "TESTING TESTING 123!"
    kafkaTemplate.send(STREAM_PENDING, greeting)

    then:
    // check that the message was received
    def received = records.poll(10, TimeUnit.SECONDS)
    received.value() == greeting.toLowerCase()
    received.key() == null

    boolean hasQueueSpan = hasQueueSpan()
    def firstProducerSpan, firstQueueSpan = null
    def secondProducerSpan, secondQueueSpan = null
    assertTraces(3) {
      trace(1) {
        // PRODUCER span 0
        span {
          firstProducerSpan = it.span
          serviceName expectedServiceName()
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
      trace(hasQueueSpan ? 3 : 2) {
        sortSpansByStart()

        if (hasQueueSpan) {
          span {
            firstQueueSpan = it.span
            serviceName splitByDestination() ? "$STREAM_PENDING" : "kafka"
            operationName "kafka.deliver"
            resourceName "$STREAM_PENDING"
            spanType "queue"
            errored false
            measured true
            childOf firstProducerSpan
            tags {
              "$Tags.COMPONENT" "java-kafka-streams"
              "$Tags.SPAN_KIND" Tags.SPAN_KIND_BROKER
              defaultTags(true)
            }
          }
        }

        // STREAMING span 0
        span {
          serviceName expectedServiceName()
          operationName "kafka.consume"
          resourceName "Consume Topic $STREAM_PENDING"
          spanType "queue"
          errored false
          measured true
          childOf hasQueueSpan ? firstQueueSpan : firstProducerSpan

          tags {
            "$Tags.COMPONENT" "java-kafka-streams"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CONSUMER
            "$InstrumentationTags.PARTITION" { it >= 0 }
            "$InstrumentationTags.OFFSET" 0
            "$InstrumentationTags.PROCESSOR_NAME" "KSTREAM-SOURCE-0000000000"
            "asdf" "testing"
            defaultTags(!hasQueueSpan)
          }
        }

        // STREAMING span 1
        span {
          secondProducerSpan = it.span
          serviceName expectedServiceName()
          operationName "kafka.produce"
          resourceName "Produce Topic $STREAM_PROCESSED"
          spanType "queue"
          errored false
          measured true
          childOf span(hasQueueSpan ? 1 : 0)

          tags {
            "$Tags.COMPONENT" "java-kafka"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_PRODUCER
            defaultTags()
          }
        }
      }
      trace(hasQueueSpan ? 2 : 1) {
        sortSpansByStart()

        if (hasQueueSpan) {
          span {
            secondQueueSpan = it.span
            serviceName splitByDestination() ? "$STREAM_PROCESSED" : "kafka"
            operationName "kafka.deliver"
            resourceName "$STREAM_PROCESSED"
            spanType "queue"
            errored false
            measured true
            childOf secondProducerSpan
            tags {
              "$Tags.COMPONENT" "java-kafka"
              "$Tags.SPAN_KIND" Tags.SPAN_KIND_BROKER
              defaultTags(true)
            }
          }
        }

        // CONSUMER span 0
        span {
          serviceName expectedServiceName()
          operationName "kafka.consume"
          resourceName "Consume Topic $STREAM_PROCESSED"
          spanType "queue"
          errored false
          measured true
          childOf hasQueueSpan ? secondQueueSpan : secondProducerSpan
          tags {
            "$Tags.COMPONENT" "java-kafka"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CONSUMER
            "$InstrumentationTags.PARTITION" { it >= 0 }
            "$InstrumentationTags.OFFSET" 0
            "$InstrumentationTags.CONSUMER_GROUP" "sender"
            "$InstrumentationTags.RECORD_QUEUE_TIME_MS" { it >= 0 }
            "testing" 123
            defaultTags(!hasQueueSpan)
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
    producerFactory?.stop()
    streams?.close()
    consumerContainer?.stop()
  }
}

class KafkaStreamsForkedTest extends KafkaStreamsTestBase {
  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig("dd.service", "KafkaStreamsTest")
    injectSysConfig("dd.kafka.legacy.tracing.enabled", "false")
  }

  @Override
  String expectedServiceName()  {
    return "KafkaStreamsTest"
  }

  @Override
  boolean hasQueueSpan() {
    return true
  }

  @Override
  boolean splitByDestination() {
    return false
  }
}

class KafkaStreamsSplitByDestinationForkedTest extends KafkaStreamsTestBase {
  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig("dd.service", "KafkaStreamsTest")
    injectSysConfig("dd.kafka.legacy.tracing.enabled", "false")
    injectSysConfig("dd.message.broker.split-by-destination", "true")
  }

  @Override
  String expectedServiceName()  {
    return "KafkaStreamsTest"
  }

  @Override
  boolean hasQueueSpan() {
    return true
  }

  @Override
  boolean splitByDestination() {
    return true
  }
}

class KafkaStreamsLegacyTracingForkedTest extends KafkaStreamsTestBase {
  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig("dd.kafka.legacy.tracing.enabled", "true")
  }

  @Override
  String expectedServiceName() {
    return "kafka"
  }

  @Override
  boolean hasQueueSpan() {
    return false
  }

  @Override
  boolean splitByDestination() {
    return false
  }
}

class KafkaStreamsDataStreamsDisabledForkedTest extends KafkaStreamsTestBase {
  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig("dd.service", "KafkaStreamsDataStreamsDisabledForkedTest")
    injectSysConfig("dd.kafka.legacy.tracing.enabled", "false")
  }

  @Override
  String expectedServiceName()  {
    return "KafkaStreamsDataStreamsDisabledForkedTest"
  }

  @Override
  boolean hasQueueSpan() {
    return true
  }

  @Override
  boolean splitByDestination() {
    return false
  }

  @Override
  boolean isDataStreamsEnabled() {
    return false
  }
}

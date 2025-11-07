import datadog.trace.agent.test.naming.VersionedNamingTestBase
import datadog.trace.api.DDTags
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
import spock.lang.Ignore
import spock.lang.Shared

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

abstract class KafkaStreamsTestBase extends VersionedNamingTestBase {
  static final STREAM_PENDING = "test.pending"
  static final STREAM_PROCESSED = "test.processed"

  @Shared
  @ClassRule
  KafkaEmbedded embeddedKafka = new KafkaEmbedded(1, true, 1, STREAM_PENDING, STREAM_PROCESSED)

  abstract boolean hasQueueSpan()

  abstract boolean splitByDestination()

  @Override
  protected boolean isDataStreamsEnabled() {
    return true
  }

  @Override
  int version() {
    0
  }

  @Override
  String operation() {
    return null
  }

  String operationForProducer() {
    "kafka.produce"
  }

  String operationForConsumer() {
    "kafka.consume"
  }

  String serviceForTimeInQueue() {
    "kafka"
  }


  @Ignore("Repeatedly fails with the wrong parent span https://github.com/DataDog/dd-trace-java/issues/3865")
  def "test kafka produce and consume with streams in-between"() {
    setup:
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
          if (isDataStreamsEnabled()) {
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
          if (isDataStreamsEnabled()) {
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
          serviceName service()
          operationName operationForProducer()
          resourceName "Produce Topic $STREAM_PENDING"
          spanType "queue"
          errored false
          measured true
          parent()
          tags {
            "$Tags.COMPONENT" "java-kafka"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_PRODUCER
            "$InstrumentationTags.MESSAGING_DESTINATION_NAME" "$STREAM_PENDING"
            if ({ isDataStreamsEnabled() }){
              "$DDTags.PATHWAY_HASH" { String }
            }
            defaultTagsNoPeerService()
          }
        }
      }
      trace(hasQueueSpan ? 3 : 2) {
        sortSpansByStart()

        if (hasQueueSpan) {
          span {
            firstQueueSpan = it.span
            serviceName splitByDestination() ? "$STREAM_PENDING" : serviceForTimeInQueue()
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
          serviceName service()
          operationName operationForConsumer()
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
            "$InstrumentationTags.MESSAGING_DESTINATION_NAME" "$STREAM_PENDING"
            "asdf" "testing"
            if ({isDataStreamsEnabled()}) {
              "$DDTags.PATHWAY_HASH" { String }
            }
            defaultTags(!hasQueueSpan)
          }
        }

        // STREAMING span 1
        span {
          secondProducerSpan = it.span
          serviceName service()
          operationName operationForProducer()
          resourceName "Produce Topic $STREAM_PROCESSED"
          spanType "queue"
          errored false
          measured true
          childOf span(hasQueueSpan ? 1 : 0)

          tags {
            "$Tags.COMPONENT" "java-kafka"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_PRODUCER
            "$InstrumentationTags.MESSAGING_DESTINATION_NAME" "$STREAM_PROCESSED"
            if ({isDataStreamsEnabled()}) {
              "$DDTags.PATHWAY_HASH" { String }
            }
            defaultTagsNoPeerService()
          }
        }
      }
      trace(hasQueueSpan ? 2 : 1) {
        sortSpansByStart()

        if (hasQueueSpan) {
          span {
            secondQueueSpan = it.span
            serviceName splitByDestination() ? "$STREAM_PROCESSED" : serviceForTimeInQueue()
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
          serviceName service()
          operationName operationForConsumer()
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
            "$InstrumentationTags.MESSAGING_DESTINATION_NAME" "$STREAM_PROCESSED"
            "testing" 123
            if ({isDataStreamsEnabled()}) {
              "$DDTags.PATHWAY_HASH" { String }
            }
            defaultTags(!hasQueueSpan)
          }
        }
      }
    }

    def headers = received.headers()
    headers.iterator().hasNext()
    new String(headers.headers("x-datadog-trace-id").iterator().next().value()) == "${TEST_WRITER[1][0].traceId}"
    new String(headers.headers("x-datadog-parent-id").iterator().next().value()) == "${TEST_WRITER[1][0].spanId}"

    if (isDataStreamsEnabled()) {
      StatsGroup originProducerPoint = TEST_DATA_STREAMS_WRITER.groups.find { it.parentHash == 0 }
      verifyAll(originProducerPoint) {
        tags.hasAllTags("direction:out", "topic:$STREAM_PENDING", "type:kafka")
      }

      StatsGroup kafkaStreamsConsumerPoint = TEST_DATA_STREAMS_WRITER.groups.find { it.parentHash == originProducerPoint.hash }
      verifyAll(kafkaStreamsConsumerPoint) {
        tags.hasAllTags("direction:in",
          "group:test-application",
          "topic:$STREAM_PENDING".toString(),
          "type:kafka")
      }

      StatsGroup kafkaStreamsProducerPoint = TEST_DATA_STREAMS_WRITER.groups.find { it.parentHash == kafkaStreamsConsumerPoint.hash }
      verifyAll(kafkaStreamsProducerPoint) {
        tags.hasAllTags("direction:out", "topic:$STREAM_PROCESSED", "type:kafka")
      }

      StatsGroup finalConsumerPoint = TEST_DATA_STREAMS_WRITER.groups.find { it.parentHash == kafkaStreamsProducerPoint.hash }
      verifyAll(finalConsumerPoint) {
        tags.hasAllTags("direction:in", "group:sender", "topic:$STREAM_PROCESSED".toString(), "type:kafka")
      }
    }

    cleanup:
    producerFactory?.stop()
    streams?.close()
    consumerContainer?.stop()
  }
}

abstract class KafkaStreamsForkedTest extends KafkaStreamsTestBase {
  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig("dd.service", "KafkaStreamsTest")
  }

  @Override
  String service()  {
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

class KafkaStreamsV0ForkedTest extends KafkaStreamsForkedTest {

}

class KafkaStreamsV1ForkedTest extends KafkaStreamsForkedTest {
  @Override
  int version() {
    1
  }

  @Override
  String operationForProducer() {
    "kafka.send"
  }

  @Override
  String operationForConsumer() {
    return "kafka.process"
  }

  @Override
  String serviceForTimeInQueue() {
    "kafka-queue"
  }

  @Override
  boolean hasQueueSpan() {
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
  String service()  {
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

abstract class KafkaStreamsLegacyTracingForkedTest extends KafkaStreamsTestBase {
  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig("dd.service", "KafkaStreamsLegacyTracingTest")
    injectSysConfig("dd.kafka.legacy.tracing.enabled", "true")
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
class KafkaStreamsLegacyTracingV0ForkedTest extends KafkaStreamsLegacyTracingForkedTest {
  @Override
  String service() {
    "kafka"
  }
}

class KafkaStreamsLegacyTracingV1ForkedTest extends KafkaStreamsLegacyTracingForkedTest {
  @Override
  String service() {
    "KafkaStreamsLegacyTracingTest"
  }
  @Override
  String operationForProducer() {
    "kafka.send"
  }

  @Override
  String operationForConsumer() {
    return "kafka.process"
  }

  @Override
  String serviceForTimeInQueue() {
    "kafka-queue"
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
  String service()  {
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

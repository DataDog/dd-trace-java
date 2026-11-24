import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.DDTags
import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.core.datastreams.StatsGroup
import datadog.trace.test.util.Flaky
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.serialization.Serdes
import org.apache.kafka.streams.KafkaStreams
import org.apache.kafka.streams.StreamsBuilder
import org.apache.kafka.streams.StreamsConfig
import org.apache.kafka.streams.kstream.KStream
import org.apache.kafka.streams.kstream.Produced
import org.apache.kafka.streams.kstream.ValueMapper
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.listener.ContainerProperties
import org.springframework.kafka.listener.KafkaMessageListenerContainer
import org.springframework.kafka.listener.MessageListener
import org.springframework.kafka.test.EmbeddedKafkaBroker
import org.springframework.kafka.test.utils.ContainerTestUtils
import org.springframework.kafka.test.utils.KafkaTestUtils
import spock.lang.Shared

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@Flaky("https://github.com/DataDog/dd-trace-java/issues/3865")
class KafkaStreamsTest extends InstrumentationSpecification {
  static final STREAM_PENDING = "test.pending"
  static final STREAM_PROCESSED = "test.processed"
  static final DSM_PENDING = "test.dsm.pending"
  static final DSM_PROCESSED = "test.dsm.processed"

  @Shared
  private EmbeddedKafkaBroker embeddedKafka

  def setupSpec() {
    embeddedKafka = new EmbeddedKafkaBroker(1, true, 1, STREAM_PENDING, STREAM_PROCESSED, DSM_PENDING, DSM_PROCESSED)
    embeddedKafka.afterPropertiesSet()
  }

  def cleanupSpec() {
    embeddedKafka?.destroy()
  }

  @Override
  protected boolean isDataStreamsEnabled() {
    return true
  }

  def "test kafka produce and consume with streams in-between"() {
    setup:
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
    StreamsBuilder builder = new StreamsBuilder()
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
            "$InstrumentationTags.MESSAGING_DESTINATION_NAME" "$STREAM_PENDING"
            "$InstrumentationTags.PARTITION" { it >= 0 }
            "$InstrumentationTags.OFFSET" { it >= 0 }
            "$InstrumentationTags.KAFKA_CLUSTER_ID" { String }
            if ({ isDataStreamsEnabled()}) {
              "$DDTags.PATHWAY_HASH" { String }
            }
            defaultTagsNoPeerService()
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
            "$InstrumentationTags.MESSAGING_DESTINATION_NAME" "$STREAM_PENDING"
            "asdf" "testing"
            if ({ isDataStreamsEnabled()}) {
              "$DDTags.PATHWAY_HASH" { String }
            }
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
            "$InstrumentationTags.MESSAGING_DESTINATION_NAME" "$STREAM_PROCESSED"
            "$InstrumentationTags.PARTITION" { it >= 0 }
            "$InstrumentationTags.OFFSET" { it >= 0 }
            "$InstrumentationTags.KAFKA_CLUSTER_ID" { String }
            if ({ isDataStreamsEnabled()}) {
              "$DDTags.PATHWAY_HASH" { String }
            }
            defaultTagsNoPeerService()
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
            "$InstrumentationTags.KAFKA_CLUSTER_ID" { String }
            "$InstrumentationTags.RECORD_QUEUE_TIME_MS" { it >= 0 }
            "$InstrumentationTags.MESSAGING_DESTINATION_NAME" "$STREAM_PROCESSED"
            "testing" 123
            if ({ isDataStreamsEnabled()}) {
              "$DDTags.PATHWAY_HASH" { String }
            }
            defaultTags(true)
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
    producerFactory?.destroy()
    streams?.close()
    consumerContainer?.stop()
  }

  def "test DSM produces one consume checkpoint per record per streams hop"() {
    setup:
    def producerProps = KafkaTestUtils.producerProps(embeddedKafka.getBrokersAsString())
    def config = new Properties()
    config.putAll(producerProps)
    config.put(StreamsConfig.APPLICATION_ID_CONFIG, "dsm-application")
    config.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String().getClass().getName())
    config.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.String().getClass().getName())

    def consumerFactory = new DefaultKafkaConsumerFactory<String, String>(KafkaTestUtils.consumerProps("dsm-sender", "false", embeddedKafka))
    def consumerContainer = new KafkaMessageListenerContainer<>(consumerFactory, new ContainerProperties(DSM_PROCESSED))
    def records = new LinkedBlockingQueue<ConsumerRecord<String, String>>()
    consumerContainer.setupMessageListener(new MessageListener<String, String>() {
        @Override
        void onMessage(ConsumerRecord<String, String> record) {
          records.add(record)
        }
      })
    consumerContainer.start()
    ContainerTestUtils.waitForAssignment(consumerContainer, embeddedKafka.getPartitionsPerTopic())

    StreamsBuilder builder = new StreamsBuilder()
    builder.stream(DSM_PENDING)
      .mapValues(new ValueMapper<String, String>() {
        @Override
        String apply(String textLine) {
          return textLine.toLowerCase()
        }
      })
      .to(DSM_PROCESSED, Produced.with(Serdes.String(), Serdes.String()))
    KafkaStreams streams = new KafkaStreams(builder.build(), config)
    streams.start()

    def producerFactory = new DefaultKafkaProducerFactory<String, String>(producerProps)
    def kafkaTemplate = new KafkaTemplate<String, String>(producerFactory)

    // fetch the metadata first, so that every produce checkpoint carries the cluster id
    kafkaTemplate.partitionsFor(DSM_PENDING)

    when:
    // several records, so the streams consume of one record cannot chain off another
    kafkaTemplate.send(DSM_PENDING, "ONE")
    kafkaTemplate.send(DSM_PENDING, "TWO")
    kafkaTemplate.send(DSM_PENDING, "THREE")
    kafkaTemplate.flush()
    def received = (1..3).collect { records.poll(20, TimeUnit.SECONDS) }
    TEST_DATA_STREAMS_WRITER.waitForGroups(4)
    // give any extra (duplicate) checkpoints a chance to be flushed too
    Thread.sleep(2000)
    TEST_DATA_STREAMS_WRITER.waitForGroups(4)
    def groups = TEST_DATA_STREAMS_WRITER.groups.findAll { it.tags.toString().contains("test.dsm.") || it.tags.toString().contains("dsm-application") }
    .unique(false) { [it.hash, it.parentHash] } // a pathway node can be flushed in several buckets

    then:
    received*.value() == ["one", "two", "three"]

    // no checkpoint uses the application id as the topic (swapped topic/group)
    groups.every { !it.tags.hasAllTags("topic:dsm-application") && !it.tags.hasAllTags("group:" + DSM_PENDING) }

    def origin = groups.findAll { it.tags.hasAllTags("direction:out", "topic:" + DSM_PENDING) }
    origin.size() == 1
    origin[0].parentHash == 0

    // exactly one consume checkpoint for the streams hop, chained to the upstream produce
    def streamsConsume = groups.findAll { it.tags.hasAllTags("direction:in", "topic:" + DSM_PENDING) }
    streamsConsume.size() == 1
    streamsConsume[0].tags.hasAllTags("direction:in", "group:dsm-application", "topic:" + DSM_PENDING, "type:kafka")
    streamsConsume[0].parentHash == origin[0].hash

    // the streams produce chains to that consume
    def streamsProduce = groups.findAll { it.tags.hasAllTags("direction:out", "topic:" + DSM_PROCESSED) }
    streamsProduce.size() == 1
    streamsProduce[0].parentHash == streamsConsume[0].hash

    def finalConsume = groups.findAll { it.tags.hasAllTags("direction:in", "topic:" + DSM_PROCESSED) }
    finalConsume.size() == 1
    finalConsume[0].tags.hasAllTags("group:dsm-sender")
    finalConsume[0].parentHash == streamsProduce[0].hash

    // the streams consume carries the cluster id, like the plain kafka-clients consume
    finalConsume[0].tags.kafkaClusterId != null
    streamsConsume[0].tags.kafkaClusterId == finalConsume[0].tags.kafkaClusterId

    groups.size() == 4

    cleanup:
    producerFactory?.destroy()
    streams?.close()
    consumerContainer?.stop()
  }
}

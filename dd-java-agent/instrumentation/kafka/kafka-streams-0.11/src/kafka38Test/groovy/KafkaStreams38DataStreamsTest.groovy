import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.core.datastreams.StatsGroup
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.serialization.Serdes
import org.apache.kafka.streams.KafkaStreams
import org.apache.kafka.streams.StreamsBuilder
import org.apache.kafka.streams.StreamsConfig
import org.apache.kafka.streams.kstream.Produced
import org.apache.kafka.streams.kstream.ValueMapper
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.listener.ContainerProperties
import org.springframework.kafka.listener.KafkaMessageListenerContainer
import org.springframework.kafka.listener.MessageListener
import org.springframework.kafka.test.EmbeddedKafkaBroker
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker
import org.springframework.kafka.test.utils.ContainerTestUtils
import org.springframework.kafka.test.utils.KafkaTestUtils
import spock.lang.Shared

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Kafka Streams 3.x with kafka-clients >= 3.8 (kafka-clients-3.8 instrumentation). The application
 * id is no longer a StreamTask constructor argument, and the kafka-clients-3.8 record collections
 * used to escape the unwrapping done by the streams instrumentation, adding consume checkpoints.
 */
class KafkaStreams38DataStreamsTest extends InstrumentationSpecification {
  static final SOURCE = "dsm38.source"
  static final SINK = "dsm38.sink"
  static final APPLICATION_ID = "dsm38-application"

  @Shared
  EmbeddedKafkaBroker embeddedKafka

  def setupSpec() {
    embeddedKafka = new EmbeddedKafkaKraftBroker(1, 1, SOURCE, SINK)
    embeddedKafka.afterPropertiesSet()
  }

  def cleanupSpec() {
    embeddedKafka?.destroy()
  }

  @Override
  protected boolean isDataStreamsEnabled() {
    return true
  }

  def "one correctly tagged consume checkpoint per record per streams hop"() {
    setup:
    def producerProps = KafkaTestUtils.producerProps(embeddedKafka.getBrokersAsString())
    def config = new Properties()
    config.putAll(producerProps)
    config.put(StreamsConfig.APPLICATION_ID_CONFIG, APPLICATION_ID)
    config.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String().getClass().getName())
    config.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.String().getClass().getName())

    def consumerFactory = new DefaultKafkaConsumerFactory<String, String>(
      KafkaTestUtils.consumerProps("dsm38-sender", "false", embeddedKafka))
    def consumerContainer = new KafkaMessageListenerContainer<>(consumerFactory, new ContainerProperties(SINK))
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
    builder.stream(SOURCE)
      .mapValues(new ValueMapper<String, String>() {
        @Override
        String apply(String textLine) {
          return textLine.toLowerCase()
        }
      })
      .to(SINK, Produced.with(Serdes.String(), Serdes.String()))
    KafkaStreams streams = new KafkaStreams(builder.build(), config)
    streams.start()

    def producerFactory = new DefaultKafkaProducerFactory<String, String>(producerProps)
    def kafkaTemplate = new KafkaTemplate<String, String>(producerFactory)

    // fetch the metadata first, so that every produce checkpoint carries the cluster id
    kafkaTemplate.partitionsFor(SOURCE)

    when:
    // several records, so the streams consume of one record cannot chain off another
    kafkaTemplate.send(SOURCE, "ONE")
    kafkaTemplate.send(SOURCE, "TWO")
    kafkaTemplate.send(SOURCE, "THREE")
    kafkaTemplate.flush()
    def received = (1..3).collect { records.poll(30, TimeUnit.SECONDS) }
    TEST_DATA_STREAMS_WRITER.waitForGroups(4)
    // give any extra (duplicate) checkpoints a chance to be flushed too
    Thread.sleep(2000)
    TEST_DATA_STREAMS_WRITER.waitForGroups(4)
    List<StatsGroup> groups = TEST_DATA_STREAMS_WRITER.groups.findAll {
      it.tags.topic?.contains("dsm38.") || it.tags.group?.contains(APPLICATION_ID)
    }.unique(false) { [it.hash, it.parentHash] } // a pathway node can be flushed in several buckets

    then:
    received*.value() == ["one", "two", "three"]

    // no checkpoint uses the application id (or nothing) as the topic: no topic/group swap
    groups.every { it.tags.topic != null && !it.tags.hasAllTags("topic:" + APPLICATION_ID) }
    groups.every { !it.tags.hasAllTags("group:" + SOURCE) }

    def origin = groups.findAll { it.tags.hasAllTags("direction:out", "topic:" + SOURCE) }
    origin.size() == 1
    origin[0].parentHash == 0

    // exactly one consume checkpoint for the streams hop, chained to the upstream produce
    def streamsConsume = groups.findAll { it.tags.hasAllTags("direction:in", "topic:" + SOURCE) }
    streamsConsume.size() == 1
    streamsConsume[0].tags.hasAllTags("direction:in", "group:" + APPLICATION_ID, "topic:" + SOURCE, "type:kafka")
    streamsConsume[0].parentHash == origin[0].hash

    // the streams produce chains to that consume
    def streamsProduce = groups.findAll { it.tags.hasAllTags("direction:out", "topic:" + SINK) }
    streamsProduce.size() == 1
    streamsProduce[0].parentHash == streamsConsume[0].hash

    def finalConsume = groups.findAll { it.tags.hasAllTags("direction:in", "topic:" + SINK) }
    finalConsume.size() == 1
    finalConsume[0].tags.hasAllTags("group:dsm38-sender")
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

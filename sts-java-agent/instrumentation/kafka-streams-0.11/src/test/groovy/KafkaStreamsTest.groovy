import stackstate.trace.agent.test.AgentTestRunner
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
import spock.lang.Timeout

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@Timeout(15)
class KafkaStreamsTest extends AgentTestRunner {
  static final STREAM_PENDING = "test.pending"
  static final STREAM_PROCESSED = "test.processed"

  @Shared
  @ClassRule
  KafkaEmbedded embeddedKafka = new KafkaEmbedded(1, true, STREAM_PENDING, STREAM_PROCESSED)

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
    WRITER_PHASER.register()
    def records = new LinkedBlockingQueue<ConsumerRecord<String, String>>()

    // setup a Kafka message listener
    consumerContainer.setupMessageListener(new MessageListener<String, String>() {
      @Override
      void onMessage(ConsumerRecord<String, String> record) {
        WRITER_PHASER.arriveAndAwaitAdvance() // ensure consistent ordering of traces
        getTestTracer().activeSpan().setTag("testing", 123)
        records.add(record)
      }
    })

    // start the container and underlying message listener
    consumerContainer.start()

    // wait until the container has the required number of assigned partitions
    ContainerTestUtils.waitForAssignment(consumerContainer, embeddedKafka.getPartitionsPerTopic())

    // CONFIGURE PROCESSOR
    final KStreamBuilder builder = new KStreamBuilder()
    KStream<String, String> textLines = builder.stream(STREAM_PENDING)
    textLines
      .mapValues(new ValueMapper<String, String>() {
      @Override
      String apply(String textLine) {
        WRITER_PHASER.arriveAndAwaitAdvance() // ensure consistent ordering of traces
        getTestTracer().activeSpan().setTag("asdf", "testing")
        return textLine.toLowerCase()
      }
    })
      .to(Serdes.String(), Serdes.String(), STREAM_PROCESSED)
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

    TEST_WRITER.waitForTraces(3)
    TEST_WRITER.size() == 3

    def t1 = TEST_WRITER.get(0)
    t1.size() == 1
    def t2 = TEST_WRITER.get(1)
    t2.size() == 2
    def t3 = TEST_WRITER.get(2)
    t3.size() == 1

    and: // PRODUCER span 0
    def t1span1 = t1[0]

    t1span1.context().operationName == "kafka.produce"
    t1span1.serviceName == "kafka"
    t1span1.resourceName == "Produce Topic $STREAM_PENDING"
    t1span1.type == "queue"
    !t1span1.context().getErrorFlag()
    t1span1.context().parentId == 0

    def t1tags1 = t1span1.context().tags
    t1tags1["component"] == "java-kafka"
    t1tags1["span.kind"] == "producer"
    t1tags1["span.type"] == "queue"
    t1tags1["span.hostname"] != null
    t1tags1["span.pid"] != 0l
    t1tags1["thread.name"] != null
    t1tags1["thread.id"] != null
    t1tags1.size() == 7

    and: // STREAMING span 0
    def t2span1 = t2[0]

    t2span1.context().operationName == "kafka.produce"
    t2span1.serviceName == "kafka"
    t2span1.resourceName == "Produce Topic $STREAM_PROCESSED"
    t2span1.type == "queue"
    !t2span1.context().getErrorFlag()

    def t2tags1 = t2span1.context().tags
    t2tags1["component"] == "java-kafka"
    t2tags1["span.kind"] == "producer"
    t2tags1["span.type"] == "queue"
    t2tags1["span.hostname"] != null
    t2tags1["span.pid"] != 0l
    t2tags1["thread.name"] != null
    t2tags1["thread.id"] != null
    t2tags1.size() == 7

    and: // STREAMING span 1
    def t2span2 = t2[1]
    t2span1.context().parentId == t2span2.context().spanId

    t2span2.context().operationName == "kafka.consume"
    t2span2.serviceName == "kafka"
    t2span2.resourceName == "Consume Topic $STREAM_PENDING"
    t2span2.type == "queue"
    !t2span2.context().getErrorFlag()
    t2span2.context().parentId == t1span1.context().spanId

    def t2tags2 = t2span2.context().tags
    t2tags2["component"] == "java-kafka"
    t2tags2["span.kind"] == "consumer"
    t1tags1["span.type"] == "queue"
    t2tags2["partition"] >= 0
    t2tags2["offset"] == 0
    t2tags2["span.hostname"] != null
    t2tags2["span.pid"] != 0l
    t2tags2["thread.name"] != null
    t2tags2["thread.id"] != null
    t2tags2["asdf"] == "testing"
    t2tags2.size() == 10

    and: // CONSUMER span 0
    def t3span1 = t3[0]

    t3span1.context().operationName == "kafka.consume"
    t3span1.serviceName == "kafka"
    t3span1.resourceName == "Consume Topic $STREAM_PROCESSED"
    t3span1.type == "queue"
    !t3span1.context().getErrorFlag()
    t3span1.context().parentId == t2span1.context().spanId

    def t3tags1 = t3span1.context().tags
    t3tags1["component"] == "java-kafka"
    t3tags1["span.kind"] == "consumer"
    t2tags2["span.type"] == "queue"
    t3tags1["partition"] >= 0
    t3tags1["offset"] == 0
    t3tags1["span.hostname"] != null
    t3tags1["span.pid"] != 0l
    t3tags1["thread.name"] != null
    t3tags1["thread.id"] != null
    t3tags1["testing"] == 123
    t3tags1.size() == 10

    def headers = received.headers()
    headers.iterator().hasNext()
    new String(headers.headers("x-stackstate-trace-id").iterator().next().value()) == "$t2span1.traceId"
    new String(headers.headers("x-stackstate-parent-id").iterator().next().value()) == "$t2span1.spanId"


    cleanup:
    producerFactory?.stop()
    streams?.close()
    consumerContainer?.stop()
  }
}

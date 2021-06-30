import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.config.TraceInstrumentationConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.junit.Rule
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.listener.KafkaMessageListenerContainer
import org.springframework.kafka.listener.MessageListener
import org.springframework.kafka.test.rule.KafkaEmbedded
import org.springframework.kafka.test.utils.ContainerTestUtils
import org.springframework.kafka.test.utils.KafkaTestUtils
import spock.lang.Unroll

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class KafkaClientCustomPropagationConfigTest extends AgentTestRunner {
  static final SHARED_TOPIC = ["topic1", "topic2", "topic3", "topic4"]

  @Override
  boolean useStrictTraceWrites() {
    // TODO fix this by making sure that spans get closed properly
    return false
  }

  @Rule
  KafkaEmbedded embeddedKafka = new KafkaEmbedded(1, true, SHARED_TOPIC[0], SHARED_TOPIC[1], SHARED_TOPIC[2], SHARED_TOPIC[3])

  @Override
  void configurePreAgent() {
    super.configurePreAgent()

    injectSysConfig("dd.kafka.e2e.duration.enabled", "true")
  }

  @Unroll
  def "test kafka client header propagation with topic filters"() {
    setup:
    def senderProps = KafkaTestUtils.senderProps(embeddedKafka.getBrokersAsString())
    def producerFactory = new DefaultKafkaProducerFactory<String, String>(senderProps)
    def kafkaTemplate = new KafkaTemplate<String, String>(producerFactory)

    // set up the Kafka consumer properties
    def consumerProperties = KafkaTestUtils.consumerProps("sender", "false", embeddedKafka)

    // create a Kafka consumer factory
    def consumerFactory = new DefaultKafkaConsumerFactory<String, String>(consumerProperties)

    // set the topic that needs to be consumed
    def containerProperties1 = containerProperties(SHARED_TOPIC[0])
    def containerProperties2 = containerProperties(SHARED_TOPIC[1])
    def containerProperties3 = containerProperties(SHARED_TOPIC[2])
    def containerProperties4 = containerProperties(SHARED_TOPIC[3])

    // create a Kafka MessageListenerContainer
    def container1 = new KafkaMessageListenerContainer<>(consumerFactory, containerProperties1)
    def container2 = new KafkaMessageListenerContainer<>(consumerFactory, containerProperties2)
    def container3 = new KafkaMessageListenerContainer<>(consumerFactory, containerProperties3)
    def container4 = new KafkaMessageListenerContainer<>(consumerFactory, containerProperties4)

    // create a thread safe queue to store the received message
    def records1 = new LinkedBlockingQueue<ConsumerRecord<String, String>>()
    def records2 = new LinkedBlockingQueue<ConsumerRecord<String, String>>()
    def records3 = new LinkedBlockingQueue<ConsumerRecord<String, String>>()
    def records4 = new LinkedBlockingQueue<ConsumerRecord<String, String>>()

    // setup a Kafka message listener
    container1.setupMessageListener(new MessageListener<String, String>() {
        @Override
        void onMessage(ConsumerRecord<String, String> record) {
          TEST_WRITER.waitForTraces(1) // ensure consistent ordering of traces
          records1.add(record)
        }
      })

    container2.setupMessageListener(new MessageListener<String, String>() {
        @Override
        void onMessage(ConsumerRecord<String, String> record) {
          TEST_WRITER.waitForTraces(1) // ensure consistent ordering of traces
          records2.add(record)
        }
      })

    container3.setupMessageListener(new MessageListener<String, String>() {
        @Override
        void onMessage(ConsumerRecord<String, String> record) {
          TEST_WRITER.waitForTraces(1) // ensure consistent ordering of traces
          records3.add(record)
        }
      })

    container4.setupMessageListener(new MessageListener<String, String>() {
        @Override
        void onMessage(ConsumerRecord<String, String> record) {
          TEST_WRITER.waitForTraces(1) // ensure consistent ordering of traces
          records4.add(record)
        }
      })

    // start the container and underlying message listener
    container1.start()
    container2.start()
    container3.start()
    container4.start()

    // wait until the container has the required number of assigned partitions
    ContainerTestUtils.waitForAssignment(container1, embeddedKafka.getPartitionsPerTopic())
    ContainerTestUtils.waitForAssignment(container2, embeddedKafka.getPartitionsPerTopic())
    ContainerTestUtils.waitForAssignment(container3, embeddedKafka.getPartitionsPerTopic())
    ContainerTestUtils.waitForAssignment(container4, embeddedKafka.getPartitionsPerTopic())

    String message = "Testing without headers for certain topics"
    injectSysConfig(TraceInstrumentationConfig.KAFKA_CLIENT_PROPAGATION_DISABLED_LIST, value)

    when:
    for (String topic : SHARED_TOPIC)
      kafkaTemplate.send(topic, message)

    then:
    // check that the message was received
    def received1 = records1.poll(5, TimeUnit.SECONDS)
    def received2 = records2.poll(5, TimeUnit.SECONDS)
    def received3 = records3.poll(5, TimeUnit.SECONDS)
    def received4 = records4.poll(5, TimeUnit.SECONDS)

    received1.headers().iterator().hasNext() == expected1
    received2.headers().iterator().hasNext() == expected2
    received3.headers().iterator().hasNext() == expected3
    received4.headers().iterator().hasNext() == expected4

    cleanup:
    producerFactory.stop()
    container1?.stop()
    container2?.stop()
    container3?.stop()
    container4?.stop()

    where:
    value                         | expected1 | expected2 | expected3 | expected4
    "topic1,topic2,topic3,topic4" | false     | false     | false     | false
    "topic1,topic2"               | false     | false     | true      | true
    "topic1"                      | false     | true      | true      | true
    ""                            | true      | true      | true      | true
    "randomTopic"                 | true      | true      | true      | true
  }

  def containerProperties(String topic) {
    try {
      // Different class names for test and latestDepTest.
      return Class.forName("org.springframework.kafka.listener.config.ContainerProperties").newInstance(topic)
    } catch (ClassNotFoundException | NoClassDefFoundError e) {
      return Class.forName("org.springframework.kafka.listener.ContainerProperties").newInstance(topic)
    }
  }

}

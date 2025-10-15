import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.config.TraceInstrumentationConfig
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.header.Headers
import org.apache.kafka.common.header.internals.RecordHeaders
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

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan
import static datadog.trace.instrumentation.kafka_clients38.KafkaDecorator.KAFKA_PRODUCE

class KafkaClientCustomPropagationConfigTest extends InstrumentationSpecification {
  static final SHARED_TOPIC = ["topic1", "topic2", "topic3", "topic4"]
  static final MESSAGE = "Testing without headers for certain topics"

  @Shared
  EmbeddedKafkaBroker embeddedKafka

  def setupSpec() {
    embeddedKafka = new EmbeddedKafkaKraftBroker(1, 2, *SHARED_TOPIC)
    embeddedKafka.afterPropertiesSet()
  }

  def cleanupSpec() {
    embeddedKafka.destroy()
  }

  static final dataTable() {
    [
      ["topic1,topic2,topic3,topic4", false, false, false, false],
      ["topic1,topic2", false, false, true, true],
      ["topic1", false, true, true, true],
      ["", true, true, true, true],
      ["randomTopic", true, true, true, true]
    ]
  }

  @Override
  boolean useStrictTraceWrites() {
    // TODO fix this by making sure that spans get closed properly
    return false
  }


  @Override
  void configurePreAgent() {
    super.configurePreAgent()

    injectSysConfig("dd.kafka.e2e.duration.enabled", "true")
  }

  def "test kafka client header propagation with topic filters"() {
    setup:
    injectSysConfig(TraceInstrumentationConfig.KAFKA_CLIENT_PROPAGATION_DISABLED_TOPICS, value as String)

    def senderProps = KafkaTestUtils.producerProps(embeddedKafka.getBrokersAsString())
    def producerFactory = new DefaultKafkaProducerFactory<String, String>(senderProps)
    def kafkaTemplate = new KafkaTemplate<String, String>(producerFactory)

    // set up the Kafka consumer properties
    def consumerProperties = KafkaTestUtils.consumerProps( embeddedKafka.getBrokersAsString(),"sender", "false")

    // create a Kafka consumer factory
    def consumerFactory = new DefaultKafkaConsumerFactory<String, String>(consumerProperties)

    // set the topic that needs to be consumed
    def containerProperties1 = new ContainerProperties(SHARED_TOPIC[0])
    def containerProperties2 = new ContainerProperties(SHARED_TOPIC[1])
    def containerProperties3 = new ContainerProperties(SHARED_TOPIC[2])
    def containerProperties4 = new ContainerProperties(SHARED_TOPIC[3])

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
    ContainerTestUtils.waitForAssignment(container1, container1.assignedPartitions.size())
    ContainerTestUtils.waitForAssignment(container2, container2.assignedPartitions.size())
    ContainerTestUtils.waitForAssignment(container3, container3.assignedPartitions.size())
    ContainerTestUtils.waitForAssignment(container4, container4.assignedPartitions.size())

    when:
    for (String topic : SHARED_TOPIC) {
      kafkaTemplate.send(topic, MESSAGE)
    }

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
    [value, expected1, expected2, expected3, expected4]<< dataTable()
  }

  def "test consumer with topic filters"() {
    setup:
    injectSysConfig(TraceInstrumentationConfig.KAFKA_CLIENT_PROPAGATION_DISABLED_TOPICS, value as String)
    def senderProps = KafkaTestUtils.producerProps(embeddedKafka.getBrokersAsString())
    def producerFactory = new DefaultKafkaProducerFactory<String, String>(senderProps)
    def kafkaTemplate = new KafkaTemplate<String, String>(producerFactory)

    // set up the Kafka consumer properties
    def consumerProperties = KafkaTestUtils.consumerProps( embeddedKafka.getBrokersAsString(),"sender", "false")

    // create a Kafka consumer factory
    def consumerFactory = new DefaultKafkaConsumerFactory<String, String>(consumerProperties)

    // set the topic that needs to be consumed
    def containerProperties1 = new ContainerProperties(SHARED_TOPIC[0])
    def containerProperties2 = new ContainerProperties(SHARED_TOPIC[1])
    def containerProperties3 = new ContainerProperties(SHARED_TOPIC[2])
    def containerProperties4 = new ContainerProperties(SHARED_TOPIC[3])

    // create a Kafka MessageListenerContainer
    def container1 = new KafkaMessageListenerContainer<>(consumerFactory, containerProperties1)
    def container2 = new KafkaMessageListenerContainer<>(consumerFactory, containerProperties2)
    def container3 = new KafkaMessageListenerContainer<>(consumerFactory, containerProperties3)
    def container4 = new KafkaMessageListenerContainer<>(consumerFactory, containerProperties4)

    // create a thread safe queue to store the received message
    def records1 = new LinkedBlockingQueue<AgentSpan>()
    def records2 = new LinkedBlockingQueue<AgentSpan>()
    def records3 = new LinkedBlockingQueue<AgentSpan>()
    def records4 = new LinkedBlockingQueue<AgentSpan>()

    // setup a Kafka message listener
    container1.setupMessageListener(new MessageListener<String, String>() {
        @Override
        void onMessage(ConsumerRecord<String, String> record) {
          TEST_WRITER.waitForTraces(1) // ensure consistent ordering of traces
          records1.add(activeSpan())
        }
      })

    container2.setupMessageListener(new MessageListener<String, String>() {
        @Override
        void onMessage(ConsumerRecord<String, String> record) {
          TEST_WRITER.waitForTraces(1) // ensure consistent ordering of traces
          records2.add(activeSpan())
        }
      })

    container3.setupMessageListener(new MessageListener<String, String>() {
        @Override
        void onMessage(ConsumerRecord<String, String> record) {
          TEST_WRITER.waitForTraces(1) // ensure consistent ordering of traces
          records3.add(activeSpan())
        }
      })

    container4.setupMessageListener(new MessageListener<String, String>() {
        @Override
        void onMessage(ConsumerRecord<String, String> record) {
          TEST_WRITER.waitForTraces(1) // ensure consistent ordering of traces
          records4.add(activeSpan())
        }
      })

    // start the container and underlying message listener
    container1.start()
    container2.start()
    container3.start()
    container4.start()

    // wait until the container has the required number of assigned partitions
    ContainerTestUtils.waitForAssignment(container1, container1.assignedPartitions.size())
    ContainerTestUtils.waitForAssignment(container2, container2.assignedPartitions.size())
    ContainerTestUtils.waitForAssignment(container3, container3.assignedPartitions.size())
    ContainerTestUtils.waitForAssignment(container4, container4.assignedPartitions.size())

    when:
    Headers header = new RecordHeaders()

    AgentSpan span = startSpan(KAFKA_PRODUCE)
    activateSpan(span).withCloseable {
      for (String topic : SHARED_TOPIC) {
        ProducerRecord record = new ProducerRecord<>(
          topic,
          0,
          null,
          MESSAGE,
          header
          )
        kafkaTemplate.send(record as ProducerRecord<String, String>)
      }
    }
    span.finish()

    then:
    // check that the message was received
    def received1 = records1.poll(5, TimeUnit.SECONDS)
    def received2 = records2.poll(5, TimeUnit.SECONDS)
    def received3 = records3.poll(5, TimeUnit.SECONDS)
    def received4 = records4.poll(5, TimeUnit.SECONDS)

    if (expected1) {
      assert received1.getTraceId() == span.getTraceId()
    } else {
      assert received1.getTraceId() != span.getTraceId()
    }
    if (expected2) {
      assert received2.getTraceId() == span.getTraceId()
    } else {
      assert received2.getTraceId() != span.getTraceId()
    }
    if (expected3) {
      assert received3.getTraceId() == span.getTraceId()
    } else {
      assert received3.getTraceId() != span.getTraceId()
    }
    if (expected4) {
      assert received4.getTraceId() == span.getTraceId()
    } else {
      assert received4.getTraceId() != span.getTraceId()
    }

    cleanup:
    producerFactory.stop()
    container1?.stop()
    container2?.stop()
    container3?.stop()
    container4?.stop()

    where:
    [value, expected1, expected2, expected3, expected4]<< dataTable()
  }
}

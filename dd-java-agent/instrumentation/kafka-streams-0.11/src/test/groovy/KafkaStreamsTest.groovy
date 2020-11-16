import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags
import datadog.trace.bootstrap.instrumentation.api.Tags
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.serialization.Serdes
import org.apache.kafka.streams.KafkaStreams
import org.apache.kafka.streams.StreamsConfig
import org.apache.kafka.streams.kstream.KStream
import org.apache.kafka.streams.kstream.ValueMapper
import org.junit.ClassRule
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.listener.BatchMessageListener
import org.springframework.kafka.listener.KafkaMessageListenerContainer
import org.springframework.kafka.listener.MessageListener
import org.springframework.kafka.test.rule.KafkaEmbedded
import org.springframework.kafka.test.utils.ContainerTestUtils
import org.springframework.kafka.test.utils.KafkaTestUtils
import spock.lang.Shared

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class KafkaStreamsTest extends AgentTestRunner {
  static final STREAM_PENDING = "test.pending"
  static final STREAM_PROCESSED = "test.processed"
  static final DIRECT = "test.direct"


  @Shared
  @ClassRule
  KafkaEmbedded embeddedKafka = new KafkaEmbedded(1, true, STREAM_PENDING, STREAM_PROCESSED, DIRECT)

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

    def containerProperties
    try {
      // Different class names for test and latestDepTest.
      containerProperties = Class.forName("org.springframework.kafka.listener.config.ContainerProperties").newInstance(STREAM_PROCESSED)
    } catch (ClassNotFoundException | NoClassDefFoundError e) {
      containerProperties = Class.forName("org.springframework.kafka.listener.ContainerProperties").newInstance(STREAM_PROCESSED)
    }
    def consumerContainer = new KafkaMessageListenerContainer<>(consumerFactory, containerProperties)

    // create a thread safe queue to store the processed message
    def records = new LinkedBlockingQueue<ConsumerRecord<String, String>>()

    // setup a Kafka message listener
    consumerContainer.setupMessageListener(new MessageListener<String, String>() {
      @Override
      void onMessage(ConsumerRecord<String, String> record) {
        // ensure consistent ordering of traces
        // this is the last processing step so we should see 2 traces here
        TEST_WRITER.waitForTraces(3)
        getTestTracer().activeSpan().setTag("testing", 123)
        records.add(record)
      }
    })

    // start the container and underlying message listener
    consumerContainer.start()

    // wait until the container has the required number of assigned partitions
    ContainerTestUtils.waitForAssignment(consumerContainer, embeddedKafka.getPartitionsPerTopic())

    // CONFIGURE PROCESSOR
    def builder
    try {
      // Different class names for test and latestDepTest.
      builder = Class.forName("org.apache.kafka.streams.kstream.KStreamBuilder").newInstance()
    } catch (ClassNotFoundException | NoClassDefFoundError e) {
      builder = Class.forName("org.apache.kafka.streams.StreamsBuilder").newInstance()
    }
    KStream<String, String> textLines = builder.stream(STREAM_PENDING)
    def values = textLines
      .mapValues(new ValueMapper<String, String>() {
        @Override
        String apply(String textLine) {
          TEST_WRITER.waitForTraces(2) // ensure consistent ordering of traces
          getTestTracer().activeSpan().setTag("asdf", "testing")
          return textLine.toLowerCase()
        }
      })

    KafkaStreams streams
    try {
      // Different api for test and latestDepTest.
      values.to(Serdes.String(), Serdes.String(), STREAM_PROCESSED)
      streams = new KafkaStreams(builder, config)
    } catch (MissingMethodException e) {
      def producer = Class.forName("org.apache.kafka.streams.kstream.Produced")
        .with(Serdes.String(), Serdes.String())
      values.to(STREAM_PROCESSED, producer)
      streams = new KafkaStreams(builder.build(), config)
    }
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

    assertTraces(4) {
      trace(1) {
        // PRODUCER span 0
        span {
          serviceName "kafka"
          operationName "kafka.produce"
          resourceName "Produce Topic $STREAM_PENDING"
          spanType "queue"
          errored false
          parent()
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
          resourceName "Consume Topic $STREAM_PENDING"
          spanType "queue"
          errored false
          childOf trace(0)[0]
          tags {
            "$Tags.COMPONENT" "java-kafka"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CONSUMER
            "$InstrumentationTags.PARTITION" { it >= 0 }
            "$InstrumentationTags.OFFSET" 0
            "$InstrumentationTags.RECORD_QUEUE_TIME_MS" { it >= 0 }
            defaultTags(true)
          }
        }
      }
      trace(2) {

        // STREAMING span 0
        span {
          serviceName "kafka"
          operationName "kafka.produce"
          resourceName "Produce Topic $STREAM_PROCESSED"
          spanType "queue"
          errored false
          childOf span(1)

          tags {
            "$Tags.COMPONENT" "java-kafka"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_PRODUCER
            defaultTags()
          }
        }

        // STREAMING span 1
        span {
          serviceName "kafka"
          operationName "kafka.consume"
          resourceName "Consume Topic $STREAM_PENDING"
          spanType "queue"
          errored false
          childOf trace(0)[0]

          tags {
            "$Tags.COMPONENT" "java-kafka"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CONSUMER
            "$InstrumentationTags.PARTITION" { it >= 0 }
            "$InstrumentationTags.OFFSET" 0
            "asdf" "testing"
            defaultTags(true)
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
          childOf trace(2)[0]
          tags {
            "$Tags.COMPONENT" "java-kafka"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CONSUMER
            "$InstrumentationTags.PARTITION" { it >= 0 }
            "$InstrumentationTags.OFFSET" 0
            "$InstrumentationTags.RECORD_QUEUE_TIME_MS" { it >= 0 }
            "testing" 123
            defaultTags(true)
          }
        }
      }
    }

    def headers = received.headers()
    headers.iterator().hasNext()
    new String(headers.headers("x-datadog-trace-id").iterator().next().value()) == "${TEST_WRITER[2][0].traceId}"
    new String(headers.headers("x-datadog-parent-id").iterator().next().value()) == "${TEST_WRITER[2][0].spanId}"


    cleanup:
    producerFactory?.stop()
    streams?.close()
    consumerContainer?.stop()
  }

  def "test kafka batch produce and batch consume"() {
    setup:
    def config = new Properties()
    def senderProps = KafkaTestUtils.senderProps(embeddedKafka.getBrokersAsString())
    config.putAll(senderProps)
    config.put(StreamsConfig.APPLICATION_ID_CONFIG, "test-application")
    config.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String().getClass().getName())
    config.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.String().getClass().getName())

    // CONFIGURE CONSUMER
    def consumerFactory = new DefaultKafkaConsumerFactory<String, String>(KafkaTestUtils.consumerProps("groupID", "false", embeddedKafka))

    def containerProperties
    try {
      // Different class names for test and latestDepTest.
      containerProperties = Class.forName("org.springframework.kafka.listener.config.ContainerProperties").newInstance(STREAM_PROCESSED)
    } catch (ClassNotFoundException | NoClassDefFoundError e) {
      containerProperties = Class.forName("org.springframework.kafka.listener.ContainerProperties").newInstance(STREAM_PROCESSED)
    }
    def consumerContainer = new KafkaMessageListenerContainer<>(consumerFactory, containerProperties)

    // create a thread safe queue to store the processed message
    def records = new LinkedBlockingQueue<ConsumerRecord<String, String>>()

    // setup a Kafka message listener
    consumerContainer.setupMessageListener(new BatchMessageListener<String, String>() {
      @Override
      void onMessage(List<ConsumerRecord<String, String>> consumerRecords) {
        println("consumerContainer record: " + consumerRecords)
        println("consumerContainer record size: " + consumerRecords.size())
        // ensure consistent ordering of traces
        // this is the last processing step so we should see 2 traces here
        TEST_WRITER.waitForTraces(3)
        //getTestTracer().activeSpan().setTag("testing", 123)
        consumerRecords.each {
          println(": $it")
          records.add(it)
        }
      }
    })

    consumerContainer.start()
    ContainerTestUtils.waitForAssignment(consumerContainer, embeddedKafka.getPartitionsPerTopic())

    def builder
    try {
      // Different class names for test and latestDepTest.
      builder = Class.forName("org.apache.kafka.streams.kstream.KStreamBuilder").newInstance()
    } catch (ClassNotFoundException | NoClassDefFoundError e) {
      builder = Class.forName("org.apache.kafka.streams.StreamsBuilder").newInstance()
    }
    KStream<String, String> textLines = builder.stream(STREAM_PENDING)
    def values = textLines
      .mapValues(new ValueMapper<String, String>() {
        @Override
        String apply(String textLine) {
          TEST_WRITER.waitForTraces(2) // ensure consistent ordering of traces
          getTestTracer().activeSpan().setTag("asdf", "testing")
          return textLine
        }
      })

    KafkaStreams streams
    try {
      // Different api for test and latestDepTest.
      values.to(Serdes.String(), Serdes.String(), STREAM_PROCESSED)
      streams = new KafkaStreams(builder, config)
    } catch (MissingMethodException e) {
      def producer = Class.forName("org.apache.kafka.streams.kstream.Produced")
        .with(Serdes.String(), Serdes.String())
      values.to(STREAM_PROCESSED, producer)
      streams = new KafkaStreams(builder.build(), config)
    }
    streams.start()

    // CONFIGURE PRODUCER
    def producerFactory = new DefaultKafkaProducerFactory<String, String>(senderProps)
    def kafkaTemplate = new KafkaTemplate<String, String>(producerFactory)

    when:
//    String greeting = "TESTING TESTING 123!"
//    kafkaTemplate.send(STREAM_PENDING, greeting)
    final List<String> messages = ["msg 1", "msg 2", "msg 3"]
    messages.each {
      println("sending '$it'")
      kafkaTemplate.send(STREAM_PENDING, it)
    }

    then:
    for (int i = 0; i < messages.size(); i++) {
      def received = records.poll(10, TimeUnit.SECONDS)
      received.value() == messages.get(0)
      received.key() == null
      def headers = received.headers()
      headers.iterator().hasNext()
      new String(headers.headers("x-datadog-trace-id").iterator().next().value()) == "${TEST_WRITER[2][0].traceId}"
      new String(headers.headers("x-datadog-parent-id").iterator().next().value()) == "${TEST_WRITER[2][0].spanId}"
    }

    assertTraces(12) {
      trace(1) {
        span {
          serviceName "kafka"
          operationName "kafka.produce"
          resourceName "Produce Topic $STREAM_PENDING"
          spanType "queue"
          errored false
          parent()
          tags {
            "$Tags.COMPONENT" "java-kafka"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_PRODUCER
            defaultTags()
          }
        }
      }
      trace(1) {
        span {
          serviceName "kafka"
          operationName "kafka.produce"
          resourceName "Produce Topic $STREAM_PENDING"
          spanType "queue"
          errored false
          parent()
          tags {
            "$Tags.COMPONENT" "java-kafka"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_PRODUCER
            defaultTags()
          }
        }
      }
      trace(1) {
        span {
          serviceName "kafka"
          operationName "kafka.produce"
          resourceName "Produce Topic $STREAM_PENDING"
          spanType "queue"
          errored false
          parent()
          tags {
            "$Tags.COMPONENT" "java-kafka"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_PRODUCER
            defaultTags()
          }
        }
      }
      //
      trace(1) {
        span {
          serviceName "kafka"
          operationName "kafka.consume"
          resourceName "Consume Topic $STREAM_PENDING"
          spanType "queue"
          errored false
          //childOf trace(0)[0]
          tags {
            "$Tags.COMPONENT" "java-kafka"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CONSUMER
            "$InstrumentationTags.PARTITION" { it >= 0 }
            "$InstrumentationTags.OFFSET"  { 0 <= it && it < 3 }
            "$InstrumentationTags.RECORD_QUEUE_TIME_MS" { it >= 0 }
            defaultTags(true)
          }
        }
      }
      trace(1) {
        span {
          serviceName "kafka"
          operationName "kafka.consume"
          resourceName "Consume Topic $STREAM_PENDING"
          spanType "queue"
          errored false
          //childOf trace(0)[0]
          tags {
            "$Tags.COMPONENT" "java-kafka"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CONSUMER
            "$InstrumentationTags.PARTITION" { it >= 0 }
            "$InstrumentationTags.OFFSET" { 0 <= it && it < 3 }
            "$InstrumentationTags.RECORD_QUEUE_TIME_MS" { it >= 0 }
            defaultTags(true)
          }
        }
      }
      trace(1) {
        span {
          serviceName "kafka"
          operationName "kafka.consume"
          resourceName "Consume Topic $STREAM_PENDING"
          spanType "queue"
          errored false
          //childOf trace(0)[0]
          tags {
            "$Tags.COMPONENT" "java-kafka"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CONSUMER
            "$InstrumentationTags.PARTITION" { it >= 0 }
            "$InstrumentationTags.OFFSET" { 0 <= it && it < 3 }
            "$InstrumentationTags.RECORD_QUEUE_TIME_MS" { it >= 0 }
            defaultTags(true)
          }
        }
      }
      trace(2) {
        span {
          serviceName "kafka"
          operationName "kafka.produce"
          resourceName "Produce Topic $STREAM_PROCESSED"
          spanType "queue"
          errored false
          childOf span(1)

          tags {
            "$Tags.COMPONENT" "java-kafka"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_PRODUCER
            defaultTags()
          }
        }

        span {
          serviceName "kafka"
          operationName "kafka.consume"
          resourceName "Consume Topic $STREAM_PENDING"
          spanType "queue"
          errored false
          //childOf trace(0)[0]

          tags {
            "$Tags.COMPONENT" "java-kafka"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CONSUMER
            "$InstrumentationTags.PARTITION" { it >= 0 }
            "$InstrumentationTags.OFFSET" { 0 <= it && it < 2 }
            "asdf" "testing"
            defaultTags(true)
          }
        }
      }
      trace(2) {
        span {
          serviceName "kafka"
          operationName "kafka.produce"
          resourceName "Produce Topic $STREAM_PROCESSED"
          spanType "queue"
          errored false
          //childOf trace(2)[0]
          tags {
            "$Tags.COMPONENT" "java-kafka"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_PRODUCER
            defaultTags(true)
          }
        }
        span {
          serviceName "kafka"
          operationName "kafka.consume"
          resourceName "Consume Topic $STREAM_PROCESSED"
          spanType "queue"
          errored false
          childOf trace(2)[0]
          tags {
            "$Tags.COMPONENT" "java-kafka"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CONSUMER
            "$InstrumentationTags.PARTITION" { it >= 0 }
            "$InstrumentationTags.OFFSET" 0
            "$InstrumentationTags.RECORD_QUEUE_TIME_MS" { it >= 0 }
            //"testing" 123
            defaultTags(true)
          }
        }
      }
    }

    cleanup:
    producerFactory?.stop()
    streams?.close()
    consumerContainer?.stop()
  }

}

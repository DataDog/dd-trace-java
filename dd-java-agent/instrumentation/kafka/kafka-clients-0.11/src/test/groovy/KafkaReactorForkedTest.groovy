import static datadog.trace.agent.test.utils.TraceUtils.basicSpan
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.core.DDSpan
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.junit.Rule
import org.springframework.kafka.test.rule.KafkaEmbedded
import org.springframework.kafka.test.utils.KafkaTestUtils
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import reactor.kafka.receiver.KafkaReceiver
import reactor.kafka.receiver.ReceiverOptions
import reactor.kafka.sender.KafkaSender
import reactor.kafka.sender.SenderOptions
import reactor.kafka.sender.SenderRecord

import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class KafkaReactorForkedTest extends InstrumentationSpecification {
  @Rule
  // create 4 partitions for more parallelism
  KafkaEmbedded embeddedKafka = new KafkaEmbedded(1, true, 4, KafkaClientTestBase.SHARED_TOPIC)

  @Override
  boolean useStrictTraceWrites() {
    false
  }

  def setup() {
    TEST_WRITER.setFilter(KafkaClientTestBase.DROP_KAFKA_POLL)
  }

  def "test reactive produce and consume"() {
    setup:
    def senderProps = KafkaTestUtils.senderProps(embeddedKafka.getBrokersAsString())

    def kafkaSender = KafkaSender.create(SenderOptions.create(senderProps))
    // set up the Kafka consumer properties
    def consumerProperties = KafkaTestUtils.consumerProps("sender", "false", embeddedKafka)
    def subscriptionReady = new CountDownLatch(embeddedKafka.getPartitionsPerTopic())

    final KafkaReceiver<String, String> kafkaReceiver = KafkaReceiver.create(ReceiverOptions.<String, String> create(consumerProperties)
    .subscription([KafkaClientTestBase.SHARED_TOPIC])
    .addAssignListener {
      it.each {
        subscriptionReady.countDown()
      }
    })

    // create a thread safe queue to store the received message
    def records = new LinkedBlockingQueue<ConsumerRecord<String, String>>()
    kafkaReceiver.receive()
    // publish on another thread to be sure we're propagating that receive span correctly
    .publishOn(Schedulers.parallel())
    .flatMap {
      receiverRecord -> {
        records.add(receiverRecord)
        receiverRecord.receiverOffset().commit()
      }
    }.subscribe()


    // wait until the container has the required number of assigned partitions
    subscriptionReady.await()

    when:
    String greeting = "Hello Reactor Kafka Sender!"
    runUnderTrace("parent") {
      kafkaSender.send(Mono.just(SenderRecord.create(new ProducerRecord<>(KafkaClientTestBase.SHARED_TOPIC, greeting), null)))
      .doOnError {
        ex -> runUnderTrace("producer exception: " + ex) {}
      }
      .doOnNext {
        runUnderTrace("producer callback") {}
      }
      .blockFirst()
      blockUntilChildSpansFinished(2)
    }
    then:
    // check that the message was received
    def received = records.poll(5, TimeUnit.SECONDS)
    received.value() == greeting
    received.key() == null


    assertTraces(2, SORT_TRACES_BY_START) {
      trace(3) {
        basicSpan(it, "parent")
        basicSpan(it, "producer callback", span(0))
        producerSpan(it, senderProps, span(0))
      }
      trace(1) {
        consumerSpan(it, consumerProperties, trace(0)[2])
      }
    }
  }

  def "test reactive 100 msg produce and consume have only one parent"() {
    setup:
    def senderProps = KafkaTestUtils.senderProps(embeddedKafka.getBrokersAsString())
    if (isDataStreamsEnabled()) {
      senderProps.put(ProducerConfig.METADATA_MAX_AGE_CONFIG, 1000)
    }

    def kafkaSender = KafkaSender.create(SenderOptions.create(senderProps))
    // set up the Kafka consumer properties
    def consumerProperties = KafkaTestUtils.consumerProps("sender", "false", embeddedKafka)
    def subscriptionReady = new CountDownLatch(embeddedKafka.getPartitionsPerTopic())

    final KafkaReceiver<String, String> kafkaReceiver = KafkaReceiver.create(ReceiverOptions.<String, String> create(consumerProperties)
    .subscription([KafkaClientTestBase.SHARED_TOPIC])
    .addAssignListener {
      it.each {
        subscriptionReady.countDown()
      }
    })

    // create a thread safe queue to store the received message
    kafkaReceiver.receive()
    // publish on another thread to be sure we're propagating that receive span correctly
    .publishOn(Schedulers.parallel())
    .flatMap {
      receiverRecord -> {
        receiverRecord.receiverOffset().commit()
      }
    }
    .subscribeOn(Schedulers.parallel())
    .subscribe()


    // wait until the container has the required number of assigned partitions
    subscriptionReady.await()

    when:
    String greeting = "Hello Reactor Kafka Sender!"
    Flux.range(0, 100)
    .flatMap {
      kafkaSender.send(Mono.just(SenderRecord.create(new ProducerRecord<>(KafkaClientTestBase.SHARED_TOPIC, greeting), null)))
    }
    .publishOn(Schedulers.parallel())
    .subscribe()
    then:
    // check that the all the consume (100) and the send (100) are reported
    TEST_WRITER.waitForTraces(200)
    Map<String, List<DDSpan>> traces = TEST_WRITER.inject([:]) {
      map, entry ->
      def key = entry.get(0).getTraceId().toString()
      map[key] = (map[key] ?: []) + entry
      return map
    }
    traces.values().each {
      assert it.size() == 2
      int produceIndex = 0
      int consumeIndex = 1
      if ("kafka.produce".contentEquals(it.get(1).getOperationName().toString())) {
        produceIndex = 1
        consumeIndex = 0
      }
      //assert that the consumer has the producer as parent and that the producer is root
      assert it.get(consumeIndex).getParentId() == it.get(produceIndex).getSpanId()
      assert it.get(produceIndex).getParentId() == 0
    }
  }

  def producerSpan(
  TraceAssert trace,
  Map<String, ?> config,
  DDSpan parentSpan = null) {
    trace.span {
      serviceName "kafka"
      operationName "kafka.produce"
      resourceName "Produce Topic $KafkaClientTestBase.SHARED_TOPIC"
      spanType "queue"
      errored false
      measured true
      if (parentSpan) {
        childOf parentSpan
      } else {
        parent()
      }
      tags {
        "$Tags.COMPONENT" "java-kafka"
        "$Tags.SPAN_KIND" Tags.SPAN_KIND_PRODUCER
        "$InstrumentationTags.KAFKA_BOOTSTRAP_SERVERS" config.get(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG)
        "$InstrumentationTags.MESSAGING_DESTINATION_NAME" "$KafkaClientTestBase.SHARED_TOPIC"
        peerServiceFrom(InstrumentationTags.KAFKA_BOOTSTRAP_SERVERS)
        defaultTags()
      }
    }
  }

  def consumerSpan(
  TraceAssert trace,
  Map<String, Object> config,
  DDSpan parentSpan = null) {
    trace.span {
      serviceName "kafka"
      operationName "kafka.consume"
      resourceName "Consume Topic $KafkaClientTestBase.SHARED_TOPIC"
      spanType "queue"
      errored false
      measured true
      if (parentSpan) {
        childOf parentSpan
      } else {
        parent()
      }
      tags {
        "$Tags.COMPONENT" "java-kafka"
        "$Tags.SPAN_KIND" Tags.SPAN_KIND_CONSUMER
        "$InstrumentationTags.PARTITION" { it >= 0 }
        "$InstrumentationTags.OFFSET" { Integer }
        "$InstrumentationTags.CONSUMER_GROUP" "sender"
        "$InstrumentationTags.KAFKA_BOOTSTRAP_SERVERS" config.get(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG)
        "$InstrumentationTags.RECORD_QUEUE_TIME_MS" { it >= 0 }
        "$InstrumentationTags.MESSAGING_DESTINATION_NAME" "$KafkaClientTestBase.SHARED_TOPIC"
        defaultTags(true)
      }
    }
  }
}

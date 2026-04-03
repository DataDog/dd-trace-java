import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.agent.test.asserts.TraceAssert
import listener.KafkaBatchCoroutineConfig
import listener.KafkaBatchCoroutineListener
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.core.DDSpan
import org.apache.kafka.clients.producer.ProducerRecord
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.kafka.config.KafkaListenerEndpointRegistry
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.test.EmbeddedKafkaBroker
import org.springframework.kafka.test.utils.ContainerTestUtils

import java.util.concurrent.TimeUnit

class KafkaBatchListenerCoroutineTest extends InstrumentationSpecification {

  private static final String TOPIC = "batch-coroutine-topic"
  private static final String CONSUMER_GROUP = "batch-coroutine-group"

  def "batch @KafkaListener suspend fun - keeps spring.consume span active during async execution"() {
    setup:
    def appContext = new AnnotationConfigApplicationContext(KafkaBatchCoroutineConfig)
    def listener = appContext.getBean(KafkaBatchCoroutineListener)
    def template = appContext.getBean(KafkaTemplate)
    def broker = appContext.getBean(EmbeddedKafkaBroker)
    def registry = appContext.getBean(KafkaListenerEndpointRegistry)
    listener.prepareAsyncObservation()

    // Wait until listener container has been assigned partitions before sending.
    registry.listenerContainers.each { container ->
      ContainerTestUtils.waitForAssignment(container, broker.partitionsPerTopic)
    }

    TEST_WRITER.clear()

    when: "two messages are sent before the consumer polls so they arrive in one batch"
    registry.listenerContainers.each { it.stop() }
    template.send(new ProducerRecord(TOPIC, "key", "hello-batch"))
    template.send(new ProducerRecord(TOPIC, "key", "hello-batch"))
    template.flush()
    registry.listenerContainers.each { it.start() }
    listener.awaitAsyncStarted()

    then: "spring.consume is still open while coroutine work is blocked"
    TEST_WRITER.waitForTraces(2)
    assert TEST_WRITER.flatten().every { it.operationName != "spring.consume" }
    assert listener.activeParentFinished == false

    when:
    listener.releaseAsyncObservation()

    then: "the listener processes the batch within 15 s"
    listener.latch.await(15, TimeUnit.SECONDS)
    listener.receivedValues == ["hello-batch", "hello-batch"]

    and: "child.work is a child of spring.consume"
    DDSpan produce1Span, produce2Span, springConsumeParent
    assertTraces(9, SORT_TRACES_BY_ID) {
      trace(1) {
        produceSpan(it)
        produce1Span = span(0)
      }
      trace(1) {
        produceSpan(it)
        produce2Span = span(0)
      }

      trace(1) { kafkaConsumeSpan(it, produce1Span, 0) }
      trace(1) { kafkaConsumeSpan(it, produce2Span, 1) }
      trace(1) { kafkaConsumeSpan(it, produce1Span, 0) }
      trace(1) { kafkaConsumeSpan(it, produce2Span, 1) }

      trace(2) {
        // consume messages in one batch, and keep spring.consume active across suspend work
        span {
          operationName "spring.consume"
          resourceName "KafkaBatchCoroutineListener.consume"
          spanType DDSpanTypes.MESSAGE_CONSUMER
          errored false
          measured true
          parent()
          tags {
            "$Tags.COMPONENT" "spring-messaging"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CONSUMER
            defaultTags(true)
          }
        }
        springConsumeParent = span(0)
        childWorkSpan(it, springConsumeParent)
      }

      trace(1) { kafkaConsumeSpan(it, produce1Span, 0) }
      trace(1) { kafkaConsumeSpan(it, produce2Span, 1) }
    }

    cleanup:
    listener?.releaseAsyncObservation()
    appContext.close()
  }

  private static void produceSpan(TraceAssert trace) {
    trace.span {
      operationName "kafka.produce"
      resourceName "Produce Topic $TOPIC"
      spanType "queue"
      errored false
      measured true
      parent()
      tags {
        "$Tags.COMPONENT" "java-kafka"
        "$Tags.SPAN_KIND" Tags.SPAN_KIND_PRODUCER
        "$InstrumentationTags.MESSAGING_DESTINATION_NAME" TOPIC
        "$InstrumentationTags.KAFKA_BOOTSTRAP_SERVERS" { String }
        peerServiceFrom(InstrumentationTags.KAFKA_BOOTSTRAP_SERVERS)
        defaultTags()
      }
    }
  }

  private static void kafkaConsumeSpan(TraceAssert trace, DDSpan parent, int offset) {
    trace.span {
      operationName "kafka.consume"
      resourceName "Consume Topic $TOPIC"
      spanType "queue"
      errored false
      measured true
      childOf parent
      tags {
        "$Tags.COMPONENT" "java-kafka"
        "$Tags.SPAN_KIND" Tags.SPAN_KIND_CONSUMER
        "$InstrumentationTags.MESSAGING_DESTINATION_NAME" TOPIC
        "$InstrumentationTags.KAFKA_BOOTSTRAP_SERVERS" { String }
        peerServiceFrom(InstrumentationTags.KAFKA_BOOTSTRAP_SERVERS)
        "$InstrumentationTags.CONSUMER_GROUP" CONSUMER_GROUP
        "$InstrumentationTags.OFFSET" offset
        "$InstrumentationTags.PARTITION" { Integer }
        "$InstrumentationTags.RECORD_QUEUE_TIME_MS" { Long }
        "$InstrumentationTags.RECORD_END_TO_END_DURATION_MS" { Long }
        defaultTags(true)
      }
    }
  }

  private static void childWorkSpan(TraceAssert trace, DDSpan parent) {
    trace.span {
      operationName "child.work"
      childOf parent
      tags { defaultTags() }
    }
  }
}

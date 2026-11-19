import static datadog.trace.agent.test.assertions.SpanMatcher.span;
import static datadog.trace.agent.test.assertions.TraceMatcher.SORT_BY_START_TIME;
import static datadog.trace.agent.test.assertions.TraceMatcher.trace;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import datadog.context.ContextScope;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.api.DDSpanTypes;
import datadog.trace.api.DDTags;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.core.DDSpan;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;
import org.springframework.kafka.test.utils.KafkaTestUtils;

class KafkaProducerSerializationForkedTest extends AbstractInstrumentationTest {
  @Test
  void serializerFailureFinishesErrorSpanAndRestoresParent() throws Exception {
    String topic = "produce-with-serializer";
    EmbeddedKafkaKraftBroker broker = new EmbeddedKafkaKraftBroker(1, 1, topic);
    SerializationException failure = new SerializationException("invalid producer value");
    StringSerializer valueSerializer =
        new StringSerializer() {
          @Override
          public byte[] serialize(String topicName, String value) {
            if ("invalid".equals(value)) {
              throw failure;
            }
            return super.serialize(topicName, value);
          }
        };
    long parentSpanId;
    try {
      broker.afterPropertiesSet();
      try (KafkaProducer<String, String> producer =
          new KafkaProducer<>(
              KafkaTestUtils.producerProps(broker.getBrokersAsString()),
              new StringSerializer(),
              valueSerializer)) {
        AgentSpan parent = startSpan("test", "parent");
        parentSpanId = parent.getSpanId();
        try (ContextScope ignored = activateSpan(parent)) {
          assertSame(
              failure,
              assertThrows(
                  SerializationException.class,
                  () -> producer.send(new ProducerRecord<>(topic, "invalid"))));
          assertSame(parent, activeSpan());
          producer.send(new ProducerRecord<>(topic, "valid")).get(15, TimeUnit.SECONDS);
          assertSame(parent, activeSpan());
        } finally {
          parent.finish();
        }
      }
    } finally {
      broker.destroy();
    }

    Pattern produce = Pattern.compile("kafka\\.produce");
    assertTraces(
        trace(
            SORT_BY_START_TIME,
            span().root().operationName("parent"),
            span()
                .childOfPrevious()
                .operationName(produce)
                .type(DDSpanTypes.MESSAGE_PRODUCER)
                .error(),
            span()
                .childOf(parentSpanId)
                .operationName(produce)
                .type(DDSpanTypes.MESSAGE_PRODUCER)
                .error(false)));
    DDSpan rejected = writer.firstTrace().stream().filter(DDSpan::isError).findFirst().get();
    assertEquals(SerializationException.class.getName(), rejected.getTag(DDTags.ERROR_TYPE));
  }
}

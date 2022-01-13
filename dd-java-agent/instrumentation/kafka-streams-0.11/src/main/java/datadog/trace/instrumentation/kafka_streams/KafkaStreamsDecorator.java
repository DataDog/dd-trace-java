package datadog.trace.instrumentation.kafka_streams;

import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.OFFSET;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.PARTITION;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.PROCESSOR_NAME;

import datadog.trace.api.Functions;
import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.MessagingClientDecorator;
import org.apache.kafka.streams.processor.internals.ProcessorNode;
import org.apache.kafka.streams.processor.internals.ProcessorRecordContext;
import org.apache.kafka.streams.processor.internals.StampedRecord;

public class KafkaStreamsDecorator extends MessagingClientDecorator {
  public static final CharSequence JAVA_KAFKA = UTF8BytesString.create("java-kafka-streams");
  public static final CharSequence KAFKA_CONSUME = UTF8BytesString.create("kafka.consume");
  public static final KafkaStreamsDecorator CONSUMER_DECORATE = new KafkaStreamsDecorator();

  private static final DDCache<CharSequence, CharSequence> RESOURCE_NAME_CACHE =
      DDCaches.newFixedSizeCache(32);
  private static final Functions.Prefix PREFIX = new Functions.Prefix("Consume Topic ");

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"kafka", "kafka-streams"};
  }

  @Override
  protected String service() {
    return "kafka";
  }

  @Override
  protected CharSequence component() {
    return JAVA_KAFKA;
  }

  @Override
  protected String spanKind() {
    return Tags.SPAN_KIND_CONSUMER;
  }

  @Override
  protected CharSequence spanType() {
    return InternalSpanTypes.MESSAGE_CONSUMER;
  }

  public void onConsume(
      final AgentSpan span, final StampedRecord record, final ProcessorNode node) {
    if (record != null) {
      onConsume(span, record.topic(), record.partition(), record.offset(), node);
    }
  }

  public void onConsume(
      final AgentSpan span, final ProcessorRecordContext record, final ProcessorNode node) {
    if (record != null) {
      onConsume(span, record.topic(), record.partition(), record.offset(), node);
    }
  }

  private void onConsume(
      AgentSpan span, String topic2, int partition, long offset, ProcessorNode node) {
    String topic = topic2 == null ? "kafka" : topic2;
    span.setResourceName(RESOURCE_NAME_CACHE.computeIfAbsent(topic, PREFIX));
    span.setTag(PARTITION, partition);
    span.setTag(OFFSET, offset);
    if (node != null) {
      span.setTag(PROCESSOR_NAME, node.name());
    }
  }
}

package datadog.trace.instrumentation.kafka_streams;

import datadog.trace.api.DDSpanTypes;
import datadog.trace.api.DDTags;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.FixedSizeCache;
import datadog.trace.bootstrap.instrumentation.api.Functions;
import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.decorator.ClientDecorator;
import org.apache.kafka.streams.processor.internals.StampedRecord;

public class KafkaStreamsDecorator extends ClientDecorator {
  public static final KafkaStreamsDecorator CONSUMER_DECORATE = new KafkaStreamsDecorator();

  private static final FixedSizeCache<String, String> RESOURCE_NAME_CACHE =
      new FixedSizeCache<>(32);
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
  protected String component() {
    return "java-kafka";
  }

  @Override
  protected String spanKind() {
    return Tags.SPAN_KIND_CONSUMER;
  }

  @Override
  protected String spanType() {
    return DDSpanTypes.MESSAGE_CONSUMER;
  }

  public void onConsume(final AgentSpan span, final StampedRecord record) {
    if (record != null) {
      final String topic = record.topic() == null ? "kafka" : record.topic();
      span.setTag(DDTags.RESOURCE_NAME, RESOURCE_NAME_CACHE.computeIfAbsent(topic, PREFIX));
      span.setTag("partition", record.partition());
      span.setTag("offset", record.offset());
      span.setTag(InstrumentationTags.DD_MEASURED, true);
    }
  }
}

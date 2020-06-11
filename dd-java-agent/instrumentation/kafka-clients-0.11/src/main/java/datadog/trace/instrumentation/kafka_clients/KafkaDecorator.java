package datadog.trace.instrumentation.kafka_clients;

import static datadog.trace.bootstrap.instrumentation.api.DDComponents.JAVA_KAFKA;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.OFFSET;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.PARTITION;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.RECORD_QUEUE_TIME_MS;

import datadog.trace.api.DDSpanTypes;
import datadog.trace.api.DDTags;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.decorator.ClientDecorator;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.record.TimestampType;

public class KafkaDecorator extends ClientDecorator {

  private final String spanKind;
  private final String spanType;

  public static final KafkaDecorator PRODUCER_DECORATE =
      new KafkaDecorator(Tags.SPAN_KIND_PRODUCER, DDSpanTypes.MESSAGE_PRODUCER);

  public static final KafkaDecorator CONSUMER_DECORATE =
      new KafkaDecorator(Tags.SPAN_KIND_CONSUMER, DDSpanTypes.MESSAGE_CONSUMER);

  protected KafkaDecorator(String spanKind, String spanType) {
    this.spanKind = spanKind;
    this.spanType = spanType;
  }

  @Override
  protected String spanType() {
    return spanType;
  }

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"kafka"};
  }

  @Override
  protected String service() {
    return "kafka";
  }

  @Override
  protected String component() {
    return JAVA_KAFKA;
  }

  @Override
  protected String spanKind() {
    return spanKind;
  }

  public void onConsume(final AgentSpan span, final ConsumerRecord record) {
    if (record != null) {
      final String topic = record.topic() == null ? "kafka" : record.topic();
      span.setTag(DDTags.RESOURCE_NAME, "Consume Topic " + topic);
      span.setTag(PARTITION, record.partition());
      span.setTag(OFFSET, record.offset());
      span.setTag(InstrumentationTags.DD_MEASURED, true);
      // don't record a duration if the message was sent from an old Kafka client
      if (record.timestampType() != TimestampType.NO_TIMESTAMP_TYPE) {
        final long produceTime = record.timestamp();
        final long consumeTime = TimeUnit.NANOSECONDS.toMillis(span.getStartTime());
        span.setTag(RECORD_QUEUE_TIME_MS, Math.max(0L, consumeTime - produceTime));
      }
    }
  }

  public void onProduce(final AgentSpan span, final ProducerRecord record) {
    if (record != null) {

      final String topic = record.topic() == null ? "kafka" : record.topic();
      if (record.partition() != null) {
        span.setTag(PARTITION, record.partition());
      }
      span.setTag(DDTags.RESOURCE_NAME, "Produce Topic " + topic);
      span.setTag(InstrumentationTags.DD_MEASURED, true);
    }
  }
}

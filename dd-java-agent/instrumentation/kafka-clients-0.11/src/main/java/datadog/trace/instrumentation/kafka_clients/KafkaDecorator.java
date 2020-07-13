package datadog.trace.instrumentation.kafka_clients;

import static datadog.trace.bootstrap.instrumentation.api.DDComponents.JAVA_KAFKA;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.OFFSET;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.PARTITION;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.RECORD_END_TO_END_DURATION_MS;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.RECORD_QUEUE_TIME_MS;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

import datadog.trace.api.Config;
import datadog.trace.api.DDSpanTypes;
import datadog.trace.api.DDTags;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.decorator.ClientDecorator;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.record.TimestampType;

public class KafkaDecorator extends ClientDecorator {

  public static final TextMapExtractAdapter GETTER =
      new TextMapExtractAdapter(Config.get().isKafkaClientBase64DecodingEnabled());

  public static final TextMapInjectAdapter SETTER = new TextMapInjectAdapter();

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
      // TODO - do we really need both? This mechanism already adds a lot of... baggage.
      // check to not record a duration if the message was sent from an old Kafka client
      if (record.timestampType() != TimestampType.NO_TIMESTAMP_TYPE) {
        long consumeTime = NANOSECONDS.toMillis(span.getStartTime());
        final long produceTime = record.timestamp();
        span.setTag(RECORD_QUEUE_TIME_MS, Math.max(0L, consumeTime - produceTime));
      }
    }
  }

  public void finishConsumerSpan(final AgentSpan span) {
    if (endToEndDurationsEnabled
        // no context propagation on tombstones, so this is always the
        // trace start if we get one
        && !Boolean.TRUE.equals(span.getTag(InstrumentationTags.TOMBSTONE))) {
      long now = System.currentTimeMillis();
      String traceStartTime = span.getBaggageItem(DDTags.TRACE_START_TIME);
      if (null != traceStartTime) {
        // we want to use the span end time, so need its duration, which is set
        // on finish, but don't want to risk modifying the span after
        // finishing it, in case it gets published, causing possible
        // (functional) race conditions with the trace processing rules.
        // getting the current time is a reasonable compromise.
        // not being defensive here because we own the lifecycle of this value
        span.setTag(
            RECORD_END_TO_END_DURATION_MS, Math.max(0L, now - Long.parseLong(traceStartTime)));
      }
      span.finish(MILLISECONDS.toMicros(now));
    } else {
      span.finish();
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

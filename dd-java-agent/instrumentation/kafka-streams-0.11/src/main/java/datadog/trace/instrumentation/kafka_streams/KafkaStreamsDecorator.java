package datadog.trace.instrumentation.kafka_streams;

import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.OFFSET;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.PARTITION;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.PROCESSOR_NAME;

import datadog.trace.api.Config;
import datadog.trace.api.Functions;
import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import datadog.trace.api.naming.SpanNaming;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.MessagingClientDecorator;
import java.util.function.Supplier;
import org.apache.kafka.streams.processor.internals.ProcessorNode;
import org.apache.kafka.streams.processor.internals.ProcessorRecordContext;
import org.apache.kafka.streams.processor.internals.StampedRecord;

public class KafkaStreamsDecorator extends MessagingClientDecorator {
  private static final String KAFKA = "kafka";
  public static final CharSequence JAVA_KAFKA = UTF8BytesString.create("java-kafka-streams");
  public static final CharSequence KAFKA_CONSUME =
      UTF8BytesString.create(
          SpanNaming.instance().namingSchema().messaging().inboundOperation(KAFKA));
  public static final CharSequence KAFKA_DELIVER = UTF8BytesString.create("kafka.deliver");

  public static final boolean KAFKA_LEGACY_TRACING = Config.get().isKafkaLegacyTracingEnabled();
  public static final boolean TIME_IN_QUEUE_ENABLED =
      Config.get().isTimeInQueueEnabled(!KAFKA_LEGACY_TRACING, KAFKA);
  public static final String KAFKA_PRODUCED_KEY = "x_datadog_kafka_produced";

  private final String spanKind;
  private final CharSequence spanType;
  private final Supplier<String> serviceNameSupplier;

  private static final DDCache<CharSequence, CharSequence> RESOURCE_NAME_CACHE =
      DDCaches.newFixedSizeCache(32);
  private static final Functions.Prefix PREFIX = new Functions.Prefix("Consume Topic ");

  public static final KafkaStreamsDecorator CONSUMER_DECORATE =
      new KafkaStreamsDecorator(
          Tags.SPAN_KIND_CONSUMER,
          InternalSpanTypes.MESSAGE_CONSUMER,
          SpanNaming.instance()
              .namingSchema()
              .messaging()
              .inboundService(KAFKA, KAFKA_LEGACY_TRACING));

  public static final KafkaStreamsDecorator BROKER_DECORATE =
      new KafkaStreamsDecorator(
          Tags.SPAN_KIND_BROKER,
          InternalSpanTypes.MESSAGE_BROKER,
          SpanNaming.instance().namingSchema().messaging().timeInQueueService(KAFKA));

  protected KafkaStreamsDecorator(
      String spanKind, CharSequence spanType, Supplier<String> serviceNameSupplier) {
    this.spanKind = spanKind;
    this.spanType = spanType;
    this.serviceNameSupplier = serviceNameSupplier;
  }

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"kafka", "kafka-streams"};
  }

  @Override
  protected String service() {
    return serviceNameSupplier.get();
  }

  @Override
  protected CharSequence component() {
    return JAVA_KAFKA;
  }

  @Override
  protected String spanKind() {
    return spanKind;
  }

  @Override
  protected CharSequence spanType() {
    return spanType;
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

  public void onTimeInQueue(final AgentSpan span, final StampedRecord record) {
    if (record != null) {
      onTimeInQueue(span, record.topic());
    }
  }

  public void onTimeInQueue(final AgentSpan span, final ProcessorRecordContext record) {
    if (record != null) {
      onTimeInQueue(span, record.topic());
    }
  }

  public void onTimeInQueue(final AgentSpan span, final String topic2) {
    String topic = topic2 == null ? "kafka" : topic2;
    span.setResourceName(topic);
    if (Config.get().isMessageBrokerSplitByDestination()) {
      span.setServiceName(topic);
    }
  }
}

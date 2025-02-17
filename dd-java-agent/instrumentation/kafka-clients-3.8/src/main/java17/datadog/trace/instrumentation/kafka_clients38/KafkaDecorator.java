package datadog.trace.instrumentation.kafka_clients38;

import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.CONSUMER_GROUP;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.KAFKA_BOOTSTRAP_SERVERS;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.MESSAGING_DESTINATION_NAME;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.OFFSET;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.PARTITION;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.RECORD_QUEUE_TIME_MS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

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
import java.util.function.Function;
import java.util.function.Supplier;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.record.TimestampType;

public class KafkaDecorator extends MessagingClientDecorator {
  private static final String KAFKA = "kafka";
  public static final CharSequence JAVA_KAFKA = UTF8BytesString.create("java-kafka");
  public static final CharSequence KAFKA_CONSUME =
      UTF8BytesString.create(
          SpanNaming.instance().namingSchema().messaging().inboundOperation(KAFKA));

  public static final CharSequence KAFKA_POLL = UTF8BytesString.create("kafka.poll");
  public static final CharSequence KAFKA_PRODUCE =
      UTF8BytesString.create(
          SpanNaming.instance().namingSchema().messaging().outboundOperation(KAFKA));
  public static final CharSequence KAFKA_DELIVER = UTF8BytesString.create("kafka.deliver");
  public static final boolean KAFKA_LEGACY_TRACING = Config.get().isKafkaLegacyTracingEnabled();
  public static final boolean TIME_IN_QUEUE_ENABLED =
      Config.get().isTimeInQueueEnabled(!KAFKA_LEGACY_TRACING, KAFKA);
  public static final String KAFKA_PRODUCED_KEY = "x_datadog_kafka_produced";
  private final String spanKind;
  private final CharSequence spanType;
  private final Supplier<String> serviceNameSupplier;

  private static final DDCache<CharSequence, CharSequence> PRODUCER_RESOURCE_NAME_CACHE =
      DDCaches.newFixedSizeCache(32);
  private static final Functions.Prefix PRODUCER_PREFIX = new Functions.Prefix("Produce Topic ");
  private static final DDCache<CharSequence, CharSequence> CONSUMER_RESOURCE_NAME_CACHE =
      DDCaches.newFixedSizeCache(32);
  private static final DDCache<ProducerConfig, CharSequence> PRODUCER_BOOSTRAP_SERVERS_CACHE =
      DDCaches.newFixedSizeWeakKeyCache(16);
  private static final Function<ProducerConfig, CharSequence> BOOTSTRAP_SERVERS_JOINER =
      pc -> String.join(",", pc.getList(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG));
  private static final Functions.Prefix CONSUMER_PREFIX = new Functions.Prefix("Consume Topic ");

  public static final KafkaDecorator PRODUCER_DECORATE =
      new KafkaDecorator(
          Tags.SPAN_KIND_PRODUCER,
          InternalSpanTypes.MESSAGE_PRODUCER,
          SpanNaming.instance()
              .namingSchema()
              .messaging()
              .outboundService(KAFKA, KAFKA_LEGACY_TRACING));

  public static final KafkaDecorator CONSUMER_DECORATE =
      new KafkaDecorator(
          Tags.SPAN_KIND_CONSUMER,
          InternalSpanTypes.MESSAGE_CONSUMER,
          SpanNaming.instance()
              .namingSchema()
              .messaging()
              .inboundService(KAFKA, KAFKA_LEGACY_TRACING));

  public static final KafkaDecorator BROKER_DECORATE =
      new KafkaDecorator(
          Tags.SPAN_KIND_BROKER,
          InternalSpanTypes.MESSAGE_BROKER,
          SpanNaming.instance().namingSchema().messaging().timeInQueueService(KAFKA));

  protected KafkaDecorator(
      String spanKind, CharSequence spanType, Supplier<String> serviceNameSupplier) {
    this.spanKind = spanKind;
    this.spanType = spanType;
    this.serviceNameSupplier = serviceNameSupplier;
  }

  @Override
  protected CharSequence spanType() {
    return spanType;
  }

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"kafka"};
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

  public void onConsume(
      final AgentSpan span,
      final ConsumerRecord record,
      String consumerGroup,
      String bootstrapServers) {
    if (record != null) {
      final String topic = record.topic() == null ? "kafka" : record.topic();
      span.setResourceName(CONSUMER_RESOURCE_NAME_CACHE.computeIfAbsent(topic, CONSUMER_PREFIX));
      span.setTag(PARTITION, record.partition());
      span.setTag(OFFSET, record.offset());
      span.setTag(MESSAGING_DESTINATION_NAME, topic);
      if (consumerGroup != null) {
        span.setTag(CONSUMER_GROUP, consumerGroup);
      }

      if (bootstrapServers != null) {
        span.setTag(KAFKA_BOOTSTRAP_SERVERS, bootstrapServers);
      }
      // TODO - do we really need both? This mechanism already adds a lot of... baggage.
      // check to not record a duration if the message was sent from an old Kafka client
      if (record.timestampType() != TimestampType.NO_TIMESTAMP_TYPE) {
        long consumeTime = NANOSECONDS.toMillis(span.getStartTime());
        final long produceTime = record.timestamp();
        span.setTag(RECORD_QUEUE_TIME_MS, Math.max(0L, consumeTime - produceTime));
      }
    }
  }

  public void onTimeInQueue(final AgentSpan span, final ConsumerRecord record) {
    if (record != null) {
      String topic = record.topic() == null ? "kafka" : record.topic();
      span.setResourceName(topic);
      if (Config.get().isMessageBrokerSplitByDestination()) {
        span.setServiceName(topic);
      }
    }
  }

  public void onProduce(
      final AgentSpan span, final ProducerRecord record, final ProducerConfig producerConfig) {
    if (record != null) {
      if (record.partition() != null) {
        span.setTag(PARTITION, record.partition());
      }
      if (producerConfig != null) {
        span.setTag(
            KAFKA_BOOTSTRAP_SERVERS,
            PRODUCER_BOOSTRAP_SERVERS_CACHE.computeIfAbsent(
                producerConfig, BOOTSTRAP_SERVERS_JOINER));
      }
      final String topic = record.topic() == null ? "kafka" : record.topic();
      span.setResourceName(PRODUCER_RESOURCE_NAME_CACHE.computeIfAbsent(topic, PRODUCER_PREFIX));
      span.setTag(MESSAGING_DESTINATION_NAME, topic);
    }
  }
}

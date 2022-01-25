package datadog.trace.instrumentation.kafka_clients;

import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.OFFSET;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.PARTITION;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.RECORD_QUEUE_TIME_MS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

import datadog.trace.api.Config;
import datadog.trace.api.Functions;
import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.MessagingClientDecorator;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.record.TimestampType;

public class KafkaDecorator extends MessagingClientDecorator {

  public static final CharSequence JAVA_KAFKA = UTF8BytesString.create("java-kafka");
  public static final CharSequence KAFKA_CONSUME = UTF8BytesString.create("kafka.consume");
  public static final CharSequence KAFKA_PRODUCE = UTF8BytesString.create("kafka.produce");
  public static final CharSequence KAFKA_DELIVER = UTF8BytesString.create("kafka.deliver");

  public static final boolean KAFKA_LEGACY_TRACING =
      Config.get().isLegacyTracingEnabled(true, "kafka");

  public static final String KAFKA_PRODUCED_KEY = "x_datadog_kafka_produced";

  private final String spanKind;
  private final CharSequence spanType;
  private final String serviceName;

  private static final DDCache<CharSequence, CharSequence> PRODUCER_RESOURCE_NAME_CACHE =
      DDCaches.newFixedSizeCache(32);
  private static final Functions.Prefix PRODUCER_PREFIX = new Functions.Prefix("Produce Topic ");
  private static final DDCache<CharSequence, CharSequence> CONSUMER_RESOURCE_NAME_CACHE =
      DDCaches.newFixedSizeCache(32);
  private static final Functions.Prefix CONSUMER_PREFIX = new Functions.Prefix("Consume Topic ");

  private static final String LOCAL_SERVICE_NAME =
      KAFKA_LEGACY_TRACING ? "kafka" : Config.get().getServiceName();

  public static final KafkaDecorator PRODUCER_DECORATE =
      new KafkaDecorator(
          Tags.SPAN_KIND_PRODUCER, InternalSpanTypes.MESSAGE_PRODUCER, LOCAL_SERVICE_NAME);

  public static final KafkaDecorator CONSUMER_DECORATE =
      new KafkaDecorator(
          Tags.SPAN_KIND_CONSUMER, InternalSpanTypes.MESSAGE_CONSUMER, LOCAL_SERVICE_NAME);

  public static final KafkaDecorator BROKER_DECORATE =
      new KafkaDecorator(
          Tags.SPAN_KIND_BROKER,
          InternalSpanTypes.MESSAGE_BROKER,
          null /* service name will be set later on */);

  protected KafkaDecorator(String spanKind, CharSequence spanType, String serviceName) {
    this.spanKind = spanKind;
    this.spanType = spanType;
    this.serviceName = serviceName;
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
    return serviceName;
  }

  @Override
  protected CharSequence component() {
    return JAVA_KAFKA;
  }

  @Override
  protected String spanKind() {
    return spanKind;
  }

  public void onConsume(final AgentSpan span, final ConsumerRecord record) {
    if (record != null) {
      final String topic = record.topic() == null ? "kafka" : record.topic();
      span.setResourceName(CONSUMER_RESOURCE_NAME_CACHE.computeIfAbsent(topic, CONSUMER_PREFIX));
      span.setTag(PARTITION, record.partition());
      span.setTag(OFFSET, record.offset());
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
      } else {
        span.setServiceName("kafka");
      }
    }
  }

  public void onProduce(final AgentSpan span, final ProducerRecord record) {
    if (record != null) {
      if (record.partition() != null) {
        span.setTag(PARTITION, record.partition());
      }
      final String topic = record.topic() == null ? "kafka" : record.topic();
      span.setResourceName(PRODUCER_RESOURCE_NAME_CACHE.computeIfAbsent(topic, PRODUCER_PREFIX));
    }
  }
}

package datadog.trace.instrumentation.kafka_streams;

import static datadog.trace.api.datastreams.DataStreamsContext.create;
import static datadog.trace.api.datastreams.DataStreamsTags.Direction.INBOUND;
import static datadog.trace.api.datastreams.DataStreamsTags.createWithGroup;
import static datadog.trace.bootstrap.instrumentation.api.AgentPropagation.DSM_CONCERN;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.traceConfig;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.OFFSET;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.PARTITION;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.PROCESSOR_NAME;
import static datadog.trace.bootstrap.instrumentation.api.ServiceNameSources.MESSAGE_BROKER_SPLIT_BY_DESTINATION;
import static datadog.trace.instrumentation.kafka_common.StreamingContext.STREAMING_CONTEXT;
import static datadog.trace.instrumentation.kafka_common.Utils.computePayloadSizeBytes;
import static datadog.trace.instrumentation.kafka_common.Utils.newPathwayOnlySpan;
import static datadog.trace.instrumentation.kafka_streams.ProcessorRecordContextSetter.PR_SETTER;
import static datadog.trace.instrumentation.kafka_streams.ProcessorRecordContextVisitor.PR_GETTER;
import static datadog.trace.instrumentation.kafka_streams.StampedRecordContextSetter.SR_SETTER;
import static datadog.trace.instrumentation.kafka_streams.StampedRecordContextVisitor.SR_GETTER;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import datadog.context.propagation.Propagator;
import datadog.context.propagation.Propagators;
import datadog.trace.api.Config;
import datadog.trace.api.Functions;
import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import datadog.trace.api.datastreams.DataStreamsContext;
import datadog.trace.api.datastreams.DataStreamsTags;
import datadog.trace.api.naming.SpanNaming;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.MessagingClientDecorator;
import datadog.trace.instrumentation.kafka_common.Utils;
import java.util.function.Supplier;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.streams.processor.internals.ProcessorNode;
import org.apache.kafka.streams.processor.internals.ProcessorRecordContext;
import org.apache.kafka.streams.processor.internals.StampedRecord;

public class KafkaStreamsDecorator extends MessagingClientDecorator {
  private static final String KAFKA = "kafka";
  // Kept in sync with the names each kafka-streams-0.11 instrumentation module passes to its own
  // super(...) constructor call, so TRACING_ENABLED can't drift from what is actually registered.
  public static final String INTEGRATION_NAME = KAFKA;
  public static final String LEGACY_INTEGRATION_NAME = "kafka-streams";
  public static final CharSequence JAVA_KAFKA = UTF8BytesString.create("java-kafka-streams");
  public static final CharSequence KAFKA_CONSUME =
      UTF8BytesString.create(
          SpanNaming.instance().namingSchema().messaging().inboundOperation(KAFKA));
  public static final CharSequence KAFKA_DELIVER = UTF8BytesString.create("kafka.deliver");

  public static final boolean KAFKA_LEGACY_TRACING = Config.get().isKafkaLegacyTracingEnabled();
  public static final boolean TIME_IN_QUEUE_ENABLED =
      Config.get().isTimeInQueueEnabled(!KAFKA_LEGACY_TRACING, KAFKA);
  public static final boolean TRACING_ENABLED =
      Utils.isTracingEnabled(INTEGRATION_NAME, LEGACY_INTEGRATION_NAME);
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
      span.setServiceName(topic, MESSAGE_BROKER_SPLIT_BY_DESTINATION);
    }
  }

  /**
   * Creates and activates the real APM consume span (and, when time-in-queue is enabled, its broker
   * parent), tags it, and reports DSM checkpoints/transactions off of it.
   *
   * <p>Pulled out of {@code StartSpanAdvice} rather than kept as a private helper there: muzzle
   * can't validate a self-reference from an advice class to its own extra static methods, since
   * advice classes aren't part of the checked helper-class set.
   */
  public static AgentSpan startTracedConsumeSpan(
      final StampedRecord record, final ProcessorNode node, final String applicationId) {
    AgentSpan span, queueSpan = null;
    long timeInQueueStart = SR_GETTER.extractTimeInQueueStart(record);
    if (timeInQueueStart == 0 || !TIME_IN_QUEUE_ENABLED) {
      span = startSpan(JAVA_KAFKA.toString(), KAFKA_CONSUME);
    } else {
      queueSpan =
          startSpan(JAVA_KAFKA.toString(), KAFKA_DELIVER, MILLISECONDS.toMicros(timeInQueueStart));
      BROKER_DECORATE.afterStart(queueSpan);
      BROKER_DECORATE.onTimeInQueue(queueSpan, record);
      span = startSpan(JAVA_KAFKA.toString(), KAFKA_CONSUME, queueSpan.spanContext());
      BROKER_DECORATE.beforeFinish(queueSpan);
      // The queueSpan will be finished after inner span has been activated to ensure that
      // spans are written out together by TraceStructureWriter when running in strict mode
    }

    DataStreamsTags tags = createWithGroup("kafka", INBOUND, applicationId, record.topic());

    final long payloadSize =
        traceConfig().isDataStreamsEnabled() ? computePayloadSizeBytes(record.value) : 0;
    reportDsmCheckpointOrInject(span, record, tags, payloadSize);

    CONSUMER_DECORATE.afterStart(span);
    CONSUMER_DECORATE.onConsume(span, record, node);
    if (null != queueSpan) {
      queueSpan.finish();
    }
    return span;
  }

  /**
   * {@link #startTracedConsumeSpan(StampedRecord, ProcessorNode, String)}, post-2.7 record type.
   */
  public static AgentSpan startTracedConsumeSpan(
      final ProcessorRecordContext record, final ProcessorNode node, final String applicationId) {
    AgentSpan span, queueSpan = null;
    long timeInQueueStart = PR_GETTER.extractTimeInQueueStart(record);
    if (timeInQueueStart == 0 || !TIME_IN_QUEUE_ENABLED) {
      span = startSpan(JAVA_KAFKA.toString(), KAFKA_CONSUME);
    } else {
      queueSpan =
          startSpan(JAVA_KAFKA.toString(), KAFKA_DELIVER, MILLISECONDS.toMicros(timeInQueueStart));
      BROKER_DECORATE.afterStart(queueSpan);
      BROKER_DECORATE.onTimeInQueue(queueSpan, record);
      span = startSpan(JAVA_KAFKA.toString(), KAFKA_CONSUME, queueSpan.spanContext());
      BROKER_DECORATE.beforeFinish(queueSpan);
      // The queueSpan will be finished after inner span has been activated to ensure that
      // spans are written out together by TraceStructureWriter when running in strict mode
    }

    DataStreamsTags tags = createWithGroup("kafka", INBOUND, applicationId, record.topic());
    long payloadSize = payloadSizeBytes(record);
    reportDsmCheckpointOrInject(span, record, tags, payloadSize);

    CONSUMER_DECORATE.afterStart(span);
    CONSUMER_DECORATE.onConsume(span, record, node);
    if (null != queueSpan) {
      queueSpan.finish();
    }
    return span;
  }

  /**
   * DSM-only mode (tracing disabled for kafka-streams, DSM enabled): never creates a real span, so
   * no span is ever written to the agent for this integration. Only the pathway
   * checkpoint/injection happens, carried by a lightweight, never-collected span shim.
   */
  public static AgentSpan startDsmOnlyPathwaySpan(
      final StampedRecord record, final String applicationId) {
    final AgentSpan localActiveSpan = activeSpan();
    final AgentSpan span =
        newPathwayOnlySpan(localActiveSpan == null ? null : localActiveSpan.spanContext());

    DataStreamsTags tags = createWithGroup("kafka", INBOUND, applicationId, record.topic());
    final long payloadSize = computePayloadSizeBytes(record.value);
    reportDsmCheckpointOrInject(span, record, tags, payloadSize);
    return span;
  }

  /** {@link #startDsmOnlyPathwaySpan(StampedRecord, String)}, post-2.7 record type. */
  public static AgentSpan startDsmOnlyPathwaySpan(
      final ProcessorRecordContext record, final String applicationId) {
    final AgentSpan localActiveSpan = activeSpan();
    final AgentSpan span =
        newPathwayOnlySpan(localActiveSpan == null ? null : localActiveSpan.spanContext());

    DataStreamsTags tags = createWithGroup("kafka", INBOUND, applicationId, record.topic());
    long payloadSize = payloadSizeBytes(record);
    reportDsmCheckpointOrInject(span, record, tags, payloadSize);
    return span;
  }

  /**
   * Reports a DSM checkpoint for {@code record}'s topic, or - when in a streaming context and
   * {@code record}'s topic is a source topic - injects the pathway context so it survives leaving
   * the topology on another instance of the application.
   */
  private static void reportDsmCheckpointOrInject(
      final AgentSpan span,
      final StampedRecord record,
      final DataStreamsTags tags,
      final long payloadSize) {
    if (STREAMING_CONTEXT.isDisabledForTopic(record.topic())) {
      AgentTracer.get()
          .getDataStreamsMonitoring()
          .setCheckpoint(span, create(tags, record.timestamp, payloadSize));
    } else if (STREAMING_CONTEXT.isSourceTopic(record.topic())) {
      Propagator dsmPropagator = Propagators.forConcern(DSM_CONCERN);
      DataStreamsContext dsmContext = create(tags, record.timestamp, payloadSize);
      dsmPropagator.inject(span.with(dsmContext), record, SR_SETTER);
    }
  }

  /** {@link #reportDsmCheckpointOrInject(AgentSpan, StampedRecord, DataStreamsTags, long)}. */
  private static void reportDsmCheckpointOrInject(
      final AgentSpan span,
      final ProcessorRecordContext record,
      final DataStreamsTags tags,
      final long payloadSize) {
    if (STREAMING_CONTEXT.isDisabledForTopic(record.topic())) {
      AgentTracer.get()
          .getDataStreamsMonitoring()
          .setCheckpoint(span, create(tags, record.timestamp(), payloadSize));
    } else if (STREAMING_CONTEXT.isSourceTopic(record.topic())) {
      Propagator dsmPropagator = Propagators.forConcern(DSM_CONCERN);
      DataStreamsContext dsmContext = create(tags, record.timestamp(), payloadSize);
      dsmPropagator.inject(span.with(dsmContext), record, PR_SETTER);
    }
  }

  private static long payloadSizeBytes(final ProcessorRecordContext record) {
    // we have to go through Object to get the RecordMetadata here because the class of `record`
    // only implements it after 2.7 (and this class is only used if v >= 2.7)
    if ((Object) record instanceof RecordMetadata) { // should always be true
      RecordMetadata metadata = (RecordMetadata) (Object) record;
      return metadata.serializedKeySize() + metadata.serializedValueSize();
    }
    return 0;
  }
}

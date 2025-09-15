package datadog.trace.instrumentation.kafka_clients38;

import static datadog.context.propagation.Propagators.defaultPropagator;
import static datadog.trace.api.datastreams.DataStreamsContext.fromTagsWithoutCheckpoint;
import static datadog.trace.api.datastreams.DataStreamsTags.Direction.OUTBOUND;
import static datadog.trace.api.datastreams.DataStreamsTags.create;
import static datadog.trace.bootstrap.instrumentation.api.AgentPropagation.DSM_CONCERN;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.kafka_clients38.KafkaDecorator.KAFKA_PRODUCE;
import static datadog.trace.instrumentation.kafka_clients38.KafkaDecorator.PRODUCER_DECORATE;
import static datadog.trace.instrumentation.kafka_clients38.KafkaDecorator.TIME_IN_QUEUE_ENABLED;
import static datadog.trace.instrumentation.kafka_common.StreamingContext.STREAMING_CONTEXT;

import datadog.context.propagation.Propagator;
import datadog.context.propagation.Propagators;
import datadog.trace.api.Config;
import datadog.trace.api.datastreams.DataStreamsContext;
import datadog.trace.api.datastreams.DataStreamsTags;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags;
import net.bytebuddy.asm.Advice;
import org.apache.kafka.clients.Metadata;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.internals.Sender;

public class ProducerAdvice {

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static AgentScope onEnter(
      @Advice.FieldValue("producerConfig") ProducerConfig producerConfig,
      @Advice.FieldValue("sender") Sender sender,
      @Advice.FieldValue("metadata") Metadata metadata,
      @Advice.Argument(value = 0, readOnly = false) ProducerRecord record,
      @Advice.Argument(value = 1, readOnly = false) Callback callback) {
    String clusterId = InstrumentationContext.get(Metadata.class, String.class).get(metadata);
    final AgentSpan parent = activeSpan();
    final AgentSpan span = startSpan(KAFKA_PRODUCE);
    PRODUCER_DECORATE.afterStart(span);
    PRODUCER_DECORATE.onProduce(span, record, producerConfig);

    callback = new KafkaProducerCallback(callback, parent, span, clusterId);

    if (record.value() == null) {
      span.setTag(InstrumentationTags.TOMBSTONE, true);
    }

    TextMapInjectAdapterInterface setter = NoopTextMapInjectAdapter.NOOP_SETTER;
    // Do not inject headers for batch versions below 2
    // This is how similar check is being done in Kafka client itself:
    // https://github.com/apache/kafka/blob/05fcfde8f69b0349216553f711fdfc3f0259c601/clients/src/main/java/org/apache/kafka/common/record/MemoryRecordsBuilder.java#L411-L412
    // Also, do not inject headers if specified by JVM option or environment variable
    // This can help in mixed client environments where clients < 0.11 that do not support
    // headers attempt to read messages that were produced by clients > 0.11 and the magic
    // value of the broker(s) is >= 2

    // Please note that the minimum magic for kafka 3.8+ is 2 so there is no need to check this
    if (Config.get().isKafkaClientPropagationEnabled()
        && !Config.get().isKafkaClientPropagationDisabledForTopic(record.topic())) {
      setter = TextMapInjectAdapter.SETTER;
    }
    DataStreamsTags tags = create("kafka", OUTBOUND, record.topic(), null, clusterId);
    try {
      defaultPropagator().inject(span, record.headers(), setter);
      if (STREAMING_CONTEXT.isDisabledForTopic(record.topic())
          || STREAMING_CONTEXT.isSinkTopic(record.topic())) {
        // inject the context in the headers, but delay sending the stats until we know the
        // message size.
        // The stats are saved in the pathway context and sent in PayloadSizeAdvice.
        Propagator dsmPropagator = Propagators.forConcern(DSM_CONCERN);
        DataStreamsContext dsmContext = fromTagsWithoutCheckpoint(tags);
        dsmPropagator.inject(span.with(dsmContext), record.headers(), setter);
        AvroSchemaExtractor.tryExtractProducer(record, span);
      }
    } catch (final IllegalStateException e) {
      // headers must be read-only from reused record. try again with new one.
      record =
          new ProducerRecord<>(
              record.topic(),
              record.partition(),
              record.timestamp(),
              record.key(),
              record.value(),
              record.headers());

      defaultPropagator().inject(span, record.headers(), setter);
      if (STREAMING_CONTEXT.isDisabledForTopic(record.topic())
          || STREAMING_CONTEXT.isSinkTopic(record.topic())) {
        Propagator dsmPropagator = Propagators.forConcern(DSM_CONCERN);
        DataStreamsContext dsmContext = fromTagsWithoutCheckpoint(tags);
        dsmPropagator.inject(span.with(dsmContext), record.headers(), setter);
        AvroSchemaExtractor.tryExtractProducer(record, span);
      }
    }
    if (TIME_IN_QUEUE_ENABLED) {
      setter.injectTimeInQueue(record.headers());
    }
    return activateSpan(span);
  }

  @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
  public static void stopSpan(
      @Advice.Enter final AgentScope scope, @Advice.Thrown final Throwable throwable) {
    PRODUCER_DECORATE.onError(scope, throwable);
    PRODUCER_DECORATE.beforeFinish(scope);
    scope.close();
  }
}

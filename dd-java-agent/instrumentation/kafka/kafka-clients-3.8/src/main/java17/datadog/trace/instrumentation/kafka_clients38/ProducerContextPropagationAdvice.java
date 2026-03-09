package datadog.trace.instrumentation.kafka_clients38;

import static datadog.context.propagation.Propagators.defaultPropagator;
import static datadog.trace.agent.tooling.InstrumenterModule.TargetSystem.CONTEXT_TRACKING;
import static datadog.trace.api.datastreams.DataStreamsContext.fromTagsWithoutCheckpoint;
import static datadog.trace.api.datastreams.DataStreamsTags.Direction.OUTBOUND;
import static datadog.trace.api.datastreams.DataStreamsTags.create;
import static datadog.trace.bootstrap.instrumentation.api.AgentPropagation.DSM_CONCERN;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.instrumentation.kafka_clients38.KafkaDecorator.TIME_IN_QUEUE_ENABLED;
import static datadog.trace.instrumentation.kafka_common.StreamingContext.STREAMING_CONTEXT;

import datadog.context.propagation.Propagator;
import datadog.context.propagation.Propagators;
import datadog.trace.agent.tooling.annotation.AppliesOn;
import datadog.trace.api.Config;
import datadog.trace.api.datastreams.DataStreamsContext;
import datadog.trace.api.datastreams.DataStreamsTags;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import org.apache.kafka.clients.Metadata;
import org.apache.kafka.clients.producer.ProducerRecord;

@AppliesOn(CONTEXT_TRACKING)
public class ProducerContextPropagationAdvice {

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static void onEnter(
      @Advice.FieldValue("metadata") Metadata metadata,
      @Advice.Argument(value = 0, readOnly = false) ProducerRecord record) {
    AgentSpan span = activeSpan();
    if (span == null) return;
    String clusterId = InstrumentationContext.get(Metadata.class, String.class).get(metadata);
    TextMapInjectAdapterInterface setter = NoopTextMapInjectAdapter.NOOP_SETTER;
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
  }
}

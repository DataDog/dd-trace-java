package datadog.trace.instrumentation.kafka_clients38;

import static datadog.trace.bootstrap.instrumentation.api.AgentPropagation.extractContextAndGetSpanContext;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.traceConfig;
import static datadog.trace.instrumentation.kafka_clients38.KafkaDecorator.JAVA_KAFKA;
import static datadog.trace.instrumentation.kafka_clients38.KafkaDecorator.KAFKA_PRODUCE;
import static datadog.trace.instrumentation.kafka_clients38.KafkaDecorator.PRODUCER_DECORATE;

import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.api.sampling.SamplingMechanism;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags;
import datadog.trace.instrumentation.kafka_common.ClusterIdHolder;
import datadog.trace.instrumentation.kafka_common.MetadataState;
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
    MetadataState metadataState =
        InstrumentationContext.get(Metadata.class, MetadataState.class).get(metadata);
    String clusterId = metadataState != null ? metadataState.clusterId : null;

    // Set cluster ID for Schema Registry instrumentation
    if (clusterId != null) {
      ClusterIdHolder.set(clusterId);
    }

    // Try to extract existing trace context from record headers
    final AgentSpanContext extractedContext =
        extractContextAndGetSpanContext(record.headers(), TextMapExtractAdapter.GETTER);

    final AgentSpan localActiveSpan = activeSpan();

    final AgentSpan span;
    final AgentSpan callbackParentSpan;

    if (extractedContext != null) {
      span = startSpan(JAVA_KAFKA.toString(), KAFKA_PRODUCE, extractedContext);
      callbackParentSpan = span;
    } else {
      span = startSpan(JAVA_KAFKA.toString(), KAFKA_PRODUCE);
      callbackParentSpan = localActiveSpan;
      // setSamplingPriority is trace-level: it resolves to the local root span. This 2-arg
      // startSpan honours the active scope, so `span` may be a child of a customer trace
      // (localActiveSpan above). Only force the DSM-only drop when `span` is the local root,
      // otherwise we would silently drop that whole customer trace.
      if (!KafkaDecorator.TRACING_ENABLED
          && traceConfig().isDataStreamsEnabled()
          && span.getLocalRootSpan() == span) {
        span.setSamplingPriority(PrioritySampling.USER_DROP, SamplingMechanism.DATA_STREAMS);
      }
    }
    PRODUCER_DECORATE.afterStart(span);
    PRODUCER_DECORATE.onProduce(span, record, producerConfig, clusterId);

    callback = new KafkaProducerCallback(callback, callbackParentSpan, span, clusterId);

    if (record.value() == null) {
      span.setTag(InstrumentationTags.TOMBSTONE, true);
    }

    return activateSpan(span);
  }

  @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
  public static void stopSpan(
      @Advice.Enter final AgentScope scope, @Advice.Thrown final Throwable throwable) {
    // Clear cluster ID from Schema Registry instrumentation
    ClusterIdHolder.clear();

    PRODUCER_DECORATE.onError(scope, throwable);
    PRODUCER_DECORATE.beforeFinish(scope);
    scope.close();
  }
}

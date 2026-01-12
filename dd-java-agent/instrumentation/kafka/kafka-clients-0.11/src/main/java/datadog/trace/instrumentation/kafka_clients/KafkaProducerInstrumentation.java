package datadog.trace.instrumentation.kafka_clients;

import static datadog.context.propagation.Propagators.defaultPropagator;
import static datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers.hasClassNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.api.datastreams.DataStreamsContext.fromTagsWithoutCheckpoint;
import static datadog.trace.api.datastreams.DataStreamsTags.Direction.OUTBOUND;
import static datadog.trace.api.datastreams.DataStreamsTags.createWithClusterId;
import static datadog.trace.bootstrap.instrumentation.api.AgentPropagation.DSM_CONCERN;
import static datadog.trace.bootstrap.instrumentation.api.AgentPropagation.extractContextAndGetSpanContext;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.kafka_clients.KafkaDecorator.KAFKA_PRODUCE;
import static datadog.trace.instrumentation.kafka_clients.KafkaDecorator.PRODUCER_DECORATE;
import static datadog.trace.instrumentation.kafka_clients.KafkaDecorator.TIME_IN_QUEUE_ENABLED;
import static datadog.trace.instrumentation.kafka_common.StreamingContext.STREAMING_CONTEXT;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPrivate;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.context.propagation.Propagator;
import datadog.context.propagation.Propagators;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.Config;
import datadog.trace.api.datastreams.AgentDataStreamsMonitoring;
import datadog.trace.api.datastreams.DataStreamsContext;
import datadog.trace.api.datastreams.DataStreamsTags;
import datadog.trace.api.datastreams.DataStreamsTransactionExtractor;
import datadog.trace.api.datastreams.StatsPoint;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.instrumentation.kafka_common.ClusterIdHolder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatcher;
import org.apache.kafka.clients.ApiVersions;
import org.apache.kafka.clients.Metadata;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.internals.Sender;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.record.RecordBatch;

@AutoService(InstrumenterModule.class)
public final class KafkaProducerInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public KafkaProducerInstrumentation() {
    super("kafka", "kafka-0.11");
  }

  @Override
  public String muzzleDirective() {
    return "before-3.8";
  }

  @Override
  public ElementMatcher.Junction<ClassLoader> classLoaderMatcher() {
    return not(hasClassNamed("org.apache.kafka.clients.MetadataRecoveryStrategy")); // < 3.8
  }

  @Override
  public String instrumentedType() {
    return "org.apache.kafka.clients.producer.KafkaProducer";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".KafkaDecorator",
      packageName + ".TextMapInjectAdapterInterface",
      packageName + ".TextMapInjectAdapter",
      packageName + ".TextMapExtractAdapter",
      packageName + ".NoopTextMapInjectAdapter",
      packageName + ".KafkaProducerCallback",
      "datadog.trace.instrumentation.kafka_common.StreamingContext",
      "datadog.trace.instrumentation.kafka_common.ClusterIdHolder",
      packageName + ".AvroSchemaExtractor",
    };
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap("org.apache.kafka.clients.Metadata", "java.lang.String");
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(named("send"))
            .and(takesArgument(0, named("org.apache.kafka.clients.producer.ProducerRecord")))
            .and(takesArgument(1, named("org.apache.kafka.clients.producer.Callback"))),
        KafkaProducerInstrumentation.class.getName() + "$ProducerAdvice");

    transformer.applyAdvice(
        isMethod()
            .and(isPrivate())
            .and(takesArgument(0, int.class))
            .and(named("ensureValidRecordSize")), // intercepting this call allows us to see the
        // estimated message size
        KafkaProducerInstrumentation.class.getName() + "$PayloadSizeAdvice");
  }

  public static class ProducerAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope onEnter(
        @Advice.FieldValue("apiVersions") final ApiVersions apiVersions,
        @Advice.FieldValue("producerConfig") ProducerConfig producerConfig,
        @Advice.FieldValue("sender") Sender sender,
        @Advice.FieldValue("metadata") Metadata metadata,
        @Advice.Argument(value = 0, readOnly = false) ProducerRecord record,
        @Advice.Argument(value = 1, readOnly = false) Callback callback) {
      String clusterId = InstrumentationContext.get(Metadata.class, String.class).get(metadata);

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
        span = startSpan(KAFKA_PRODUCE, extractedContext);
        callbackParentSpan = span;
      } else {
        span = startSpan(KAFKA_PRODUCE);
        callbackParentSpan = localActiveSpan;
      }
      PRODUCER_DECORATE.afterStart(span);
      PRODUCER_DECORATE.onProduce(span, record, producerConfig);

      callback = new KafkaProducerCallback(callback, callbackParentSpan, span, clusterId);

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
      if (apiVersions.maxUsableProduceMagic() >= RecordBatch.MAGIC_VALUE_V2
          && Config.get().isKafkaClientPropagationEnabled()
          && !Config.get().isKafkaClientPropagationDisabledForTopic(record.topic())) {
        setter = TextMapInjectAdapter.SETTER;
      }
      DataStreamsTags tags = createWithClusterId("kafka", OUTBOUND, record.topic(), clusterId);
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

      // track transactions on produce
      AgentDataStreamsMonitoring dataStreamsMonitoring =
          AgentTracer.get().getDataStreamsMonitoring();
      List<DataStreamsTransactionExtractor> extractors =
          dataStreamsMonitoring.getTransactionExtractorsByType(
              DataStreamsTransactionExtractor.Type.KAFKA_PRODUCE_HEADERS);
      if (extractors != null) {
        for (DataStreamsTransactionExtractor extractor : extractors) {
          Header header = record.headers().lastHeader(extractor.getValue());
          if (header != null && header.value() != null) {
            String transactionId = new String(header.value(), StandardCharsets.UTF_8);
            dataStreamsMonitoring.trackTransaction(transactionId, extractor.getName());
            span.setTag(Tags.DSM_TRANSACTION_ID, transactionId);
            span.setTag(Tags.DSM_TRANSACTION_CHECKPOINT, extractor.getName());
          }
        }
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

  public static class PayloadSizeAdvice {

    /**
     * Instrumentation for the method KafkaProducer.ensureValidRecordSize that is called as part of
     * sending a kafka payload. This gives us access to an estimate of the payload size "for free",
     * that we send as a metric.
     */
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(@Advice.Argument(value = 0) int estimatedPayloadSize) {
      StatsPoint saved = activeSpan().context().getPathwayContext().getSavedStats();
      if (saved != null) {
        // create new stats including the payload size
        StatsPoint updated =
            new StatsPoint(
                saved.getTags(),
                saved.getHash(),
                saved.getParentHash(),
                saved.getAggregationHash(),
                saved.getTimestampNanos(),
                saved.getPathwayLatencyNano(),
                saved.getEdgeLatencyNano(),
                estimatedPayloadSize,
                saved.getServiceNameOverride());
        // then send the point
        AgentTracer.get().getDataStreamsMonitoring().add(updated);
      }
    }
  }
}

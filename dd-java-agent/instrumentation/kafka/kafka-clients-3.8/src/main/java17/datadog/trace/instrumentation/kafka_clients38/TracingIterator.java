package datadog.trace.instrumentation.kafka_clients38;

import static datadog.trace.api.datastreams.DataStreamsContext.create;
import static datadog.trace.api.datastreams.DataStreamsTags.Direction.INBOUND;
import static datadog.trace.api.datastreams.DataStreamsTags.create;
import static datadog.trace.bootstrap.instrumentation.api.AgentPropagation.DSM_CONCERN;
import static datadog.trace.bootstrap.instrumentation.api.AgentPropagation.extractContextAndGetSpanContext;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateNext;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.closePrevious;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.traceConfig;
import static datadog.trace.instrumentation.kafka_clients38.KafkaDecorator.JAVA_KAFKA;
import static datadog.trace.instrumentation.kafka_clients38.TextMapExtractAdapter.GETTER;
import static datadog.trace.instrumentation.kafka_clients38.TextMapInjectAdapter.SETTER;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import datadog.context.Context;
import datadog.context.propagation.Propagator;
import datadog.context.propagation.Propagators;
import datadog.trace.api.Config;
import datadog.trace.api.InstrumenterConfig;
import datadog.trace.api.datastreams.DataStreamsContext;
import datadog.trace.api.datastreams.DataStreamsTags;
import datadog.trace.api.datastreams.DataStreamsTransactionExtractor;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags;
import datadog.trace.instrumentation.kafka_common.StreamingContext;
import datadog.trace.instrumentation.kafka_common.Utils;
import java.util.Iterator;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TracingIterator implements Iterator<ConsumerRecord<?, ?>> {

  private static final Logger log = LoggerFactory.getLogger(TracingIterator.class);

  private final Iterator<ConsumerRecord<?, ?>> delegateIterator;
  private final CharSequence operationName;
  private final KafkaDecorator decorator;
  private final String group;
  private final String clusterId;
  private final String bootstrapServers;

  public TracingIterator(
      final Iterator<ConsumerRecord<?, ?>> delegateIterator,
      final CharSequence operationName,
      final KafkaDecorator decorator,
      String group,
      String clusterId,
      String bootstrapServers) {
    this.delegateIterator = delegateIterator;
    this.operationName = operationName;
    this.decorator = decorator;
    this.group = group;
    this.clusterId = clusterId;
    this.bootstrapServers = bootstrapServers;
  }

  @Override
  public boolean hasNext() {
    boolean moreRecords = delegateIterator.hasNext();
    if (!moreRecords) {
      // no more records, use this as a signal to close the last iteration scope
      if (InstrumenterConfig.get().isLegacyContextManagerEnabled()) {
        closePrevious(true);
      } else {
        final AgentSpan previousSpan = AgentSpan.fromContext(Context.root().swap());
        if (previousSpan != null) {
          previousSpan.finishWithEndToEnd();
        }
      }
    }
    return moreRecords;
  }

  @Override
  public ConsumerRecord<?, ?> next() {
    final ConsumerRecord<?, ?> next = delegateIterator.next();
    startNewRecordSpan(next);
    return next;
  }

  protected void startNewRecordSpan(ConsumerRecord<?, ?> val) {
    try {
      if (InstrumenterConfig.get().isLegacyContextManagerEnabled()) {
        closePrevious(true);
      } else if (val == null) { // previous message span was the last
        final AgentSpan previousSpan = AgentSpan.fromContext(Context.root().swap());
        if (previousSpan != null) {
          previousSpan.finishWithEndToEnd();
        }
      }
      if (val != null) {
        final AgentSpan span =
            !KafkaDecorator.TRACING_ENABLED && traceConfig().isDataStreamsEnabled()
                ? startDsmOnlyPathwaySpan(val)
                : startTracedConsumeSpan(val);
        if (InstrumenterConfig.get().isLegacyContextManagerEnabled()) {
          activateNext(span);
        } else {
          final AgentSpan previousSpan = AgentSpan.fromContext(span.swap());
          if (previousSpan != null) {
            previousSpan.finishWithEndToEnd();
          }
        }
      }
    } catch (final Exception e) {
      log.debug("Error starting new record span", e);
    }
  }

  /**
   * Creates and activates the real APM consume span (and, when time-in-queue is enabled, its broker
   * parent), tags it, and reports DSM checkpoints/transactions off of it.
   */
  private AgentSpan startTracedConsumeSpan(ConsumerRecord<?, ?> val) {
    AgentSpan span, queueSpan = null;
    if (!Config.get().isKafkaClientPropagationDisabledForTopic(val.topic())) {
      final AgentSpanContext spanContext = extractContextAndGetSpanContext(val.headers(), GETTER);
      long timeInQueueStart = GETTER.extractTimeInQueueStart(val.headers());
      if (timeInQueueStart == 0 || !KafkaDecorator.TIME_IN_QUEUE_ENABLED) {
        span = startSpan(JAVA_KAFKA.toString(), operationName, spanContext);
      } else {
        queueSpan =
            startSpan(
                JAVA_KAFKA.toString(),
                KafkaDecorator.KAFKA_DELIVER,
                spanContext,
                MILLISECONDS.toMicros(timeInQueueStart));
        KafkaDecorator.BROKER_DECORATE.afterStart(queueSpan);
        KafkaDecorator.BROKER_DECORATE.onTimeInQueue(queueSpan, val);
        span = startSpan(JAVA_KAFKA.toString(), operationName, queueSpan.spanContext());
        KafkaDecorator.BROKER_DECORATE.beforeFinish(queueSpan);
        // The queueSpan will be finished after inner span has been activated to ensure that
        // spans are written out together by TraceStructureWriter when running in strict mode
      }

      DataStreamsTags tags = create("kafka", INBOUND, val.topic(), group, clusterId);
      final long payloadSize =
          traceConfig().isDataStreamsEnabled() ? Utils.computePayloadSizeBytes(val) : 0;
      reportDsmCheckpointOrInject(span, val, tags, payloadSize);
    } else {
      span = startSpan(JAVA_KAFKA.toString(), operationName, null);
    }
    if (val.value() == null) {
      span.setTag(InstrumentationTags.TOMBSTONE, true);
    }
    decorator.afterStart(span);
    decorator.onConsume(span, val, group, clusterId, bootstrapServers);
    if (null != queueSpan) {
      queueSpan.finish();
    }

    trackDsmConsumeTransaction(span, val);
    return span;
  }

  /**
   * DSM-only mode (tracing disabled for kafka, DSM enabled): never creates a real span, so no span
   * is ever written to the agent for this integration. Only the pathway checkpoint/injection and
   * transaction tracking happen, carried by a lightweight, never-collected span shim.
   */
  private AgentSpan startDsmOnlyPathwaySpan(ConsumerRecord<?, ?> val) {
    AgentSpan span;
    if (!Config.get().isKafkaClientPropagationDisabledForTopic(val.topic())) {
      final AgentSpanContext extractedContext =
          extractContextAndGetSpanContext(val.headers(), GETTER);
      span = Utils.newPathwayOnlySpan(extractedContext);
      DataStreamsTags tags = create("kafka", INBOUND, val.topic(), group, clusterId);
      final long payloadSize = Utils.computePayloadSizeBytes(val);
      reportDsmCheckpointOrInject(span, val, tags, payloadSize);
    } else {
      span = Utils.newPathwayOnlySpan(null);
    }
    trackDsmConsumeTransaction(span, val);
    return span;
  }

  /**
   * Reports a DSM checkpoint for {@code val}'s topic, or - when in a streaming context and {@code
   * val}'s topic is a source topic - injects the pathway context into its headers so it survives
   * leaving the topology on another instance of the application.
   */
  private void reportDsmCheckpointOrInject(
      AgentSpan span, ConsumerRecord<?, ?> val, DataStreamsTags tags, long payloadSize) {
    if (StreamingContext.STREAMING_CONTEXT.isDisabledForTopic(val.topic())) {
      AgentTracer.get()
          .getDataStreamsMonitoring()
          .setCheckpoint(span, create(tags, val.timestamp(), payloadSize));
    } else if (StreamingContext.STREAMING_CONTEXT.isSourceTopic(val.topic())) {
      // when we're in a streaming context we want to consume only from source topics
      Propagator dsmPropagator = Propagators.forConcern(DSM_CONCERN);
      DataStreamsContext dsmContext = create(tags, val.timestamp(), payloadSize);
      dsmPropagator.inject(span.with(dsmContext), val.headers(), SETTER);
    }
  }

  private void trackDsmConsumeTransaction(AgentSpan span, ConsumerRecord<?, ?> val) {
    AgentTracer.get()
        .getDataStreamsMonitoring()
        .trackTransaction(
            span,
            DataStreamsTransactionExtractor.Type.KAFKA_CONSUME_HEADERS,
            val.headers(),
            Utils.DSM_TRANSACTION_SOURCE_READER);
  }

  @Override
  public void remove() {
    delegateIterator.remove();
  }
}

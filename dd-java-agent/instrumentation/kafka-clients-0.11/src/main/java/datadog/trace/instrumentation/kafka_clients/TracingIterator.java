package datadog.trace.instrumentation.kafka_clients;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateNext;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.closePrevious;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.propagate;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.kafka_clients.KafkaDecorator.BROKER_DECORATE;
import static datadog.trace.instrumentation.kafka_clients.KafkaDecorator.KAFKA_DELIVER;
import static datadog.trace.instrumentation.kafka_clients.KafkaDecorator.KAFKA_LEGACY_TRACING;
import static datadog.trace.instrumentation.kafka_clients.TextMapExtractAdapter.GETTER;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan.Context;
import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags;
import datadog.trace.bootstrap.instrumentation.api.PathwayContext;
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

  public TracingIterator(
      final Iterator<ConsumerRecord<?, ?>> delegateIterator,
      final CharSequence operationName,
      final KafkaDecorator decorator,
      String group) {
    this.delegateIterator = delegateIterator;
    this.operationName = operationName;
    this.decorator = decorator;
    this.group = group;
  }

  @Override
  public boolean hasNext() {
    boolean moreRecords = delegateIterator.hasNext();
    if (!moreRecords) {
      // no more records, use this as a signal to close the last iteration scope
      closePrevious(true);
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
      closePrevious(true);
      AgentSpan span, queueSpan = null;
      if (val != null) {
        if (!Config.get().isKafkaClientPropagationDisabledForTopic(val.topic())) {
          final Context spanContext = propagate().extract(val.headers(), GETTER);
          if (spanContext != null) {
            PathwayContext pc = spanContext.getPathwayContext();
            if (pc != null) {
              // todo[piochelepiotr] Add edge tags even if there is no service before this one.
              pc.setQueueTags("kafka", group, val.topic());
            }
          }
          long timeInQueueStart = GETTER.extractTimeInQueueStart(val.headers());
          if (timeInQueueStart == 0 || KAFKA_LEGACY_TRACING) {
            span = startSpan(operationName, spanContext);
          } else {
            queueSpan =
                startSpan(
                    KAFKA_DELIVER, spanContext, MILLISECONDS.toMicros(timeInQueueStart), false);
            BROKER_DECORATE.afterStart(queueSpan);
            BROKER_DECORATE.onTimeInQueue(queueSpan, val);
            span = startSpan(operationName, queueSpan.context());
            BROKER_DECORATE.beforeFinish(queueSpan);
            // The queueSpan will be finished after inner span has been activated to ensure that
            // spans are written out together by TraceStructureWriter when running in strict mode
          }
        } else {
          span = startSpan(operationName, null);
        }
        if (val.value() == null) {
          span.setTag(InstrumentationTags.TOMBSTONE, true);
        }
        decorator.afterStart(span);
        decorator.onConsume(span, val, group);
        activateNext(span);
        if (null != queueSpan) {
          queueSpan.finish();
        }
      }
    } catch (final Exception e) {
      log.debug("Error starting new record span", e);
    }
  }

  @Override
  public void remove() {
    delegateIterator.remove();
  }
}

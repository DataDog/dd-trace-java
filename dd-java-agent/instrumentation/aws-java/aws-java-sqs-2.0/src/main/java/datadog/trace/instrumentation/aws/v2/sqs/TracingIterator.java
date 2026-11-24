package datadog.trace.instrumentation.aws.v2.sqs;

import static datadog.trace.api.datastreams.DataStreamsContext.create;
import static datadog.trace.api.datastreams.DataStreamsTags.Direction.INBOUND;
import static datadog.trace.api.datastreams.DataStreamsTags.create;
import static datadog.trace.bootstrap.instrumentation.api.AgentPropagation.extractContextAndGetSpanContext;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateNext;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.closePrevious;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.traceConfig;
import static datadog.trace.bootstrap.instrumentation.api.URIUtils.urlFileName;
import static datadog.trace.instrumentation.aws.v2.sqs.MessageExtractAdapter.GETTER;
import static datadog.trace.instrumentation.aws.v2.sqs.SqsDecorator.BROKER_DECORATE;
import static datadog.trace.instrumentation.aws.v2.sqs.SqsDecorator.COMPONENT_NAME;
import static datadog.trace.instrumentation.aws.v2.sqs.SqsDecorator.CONSUMER_DECORATE;
import static datadog.trace.instrumentation.aws.v2.sqs.SqsDecorator.SQS_INBOUND_OPERATION;
import static datadog.trace.instrumentation.aws.v2.sqs.SqsDecorator.SQS_TIME_IN_QUEUE_OPERATION;
import static datadog.trace.instrumentation.aws.v2.sqs.SqsDecorator.TIME_IN_QUEUE_ENABLED;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import datadog.context.Context;
import datadog.trace.api.Config;
import datadog.trace.api.InstrumenterConfig;
import datadog.trace.api.datastreams.DataStreamsTags;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.util.Iterator;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.sqs.model.Message;

public class TracingIterator<L extends Iterator<Message>> implements Iterator<Message> {
  private static final Logger log = LoggerFactory.getLogger(TracingIterator.class);

  protected final L delegate;
  private final String queueUrl;
  private final String queueName;
  private final String requestId;
  private AgentSpanContext batchContext;

  public TracingIterator(L delegate, String queueUrl, String requestId) {
    this.delegate = delegate;
    this.queueUrl = queueUrl;
    this.queueName = queueUrl == null ? "" : urlFileName(queueUrl);
    this.requestId = requestId;
  }

  @Override
  public boolean hasNext() {
    boolean moreMessages = delegate.hasNext();
    if (!moreMessages) {
      // no more messages, use this as a signal to close the last iteration scope
      if (InstrumenterConfig.get().isLegacyContextManagerEnabled()) {
        closePrevious(true);
      } else {
        final AgentSpan previousSpan = AgentSpan.fromContext(Context.root().swap());
        if (previousSpan != null) {
          previousSpan.finishWithEndToEnd();
        }
      }
    }
    return moreMessages;
  }

  @Override
  public Message next() {
    Message next = delegate.next();
    startNewMessageSpan(next);
    return next;
  }

  protected void startNewMessageSpan(Message message) {
    try {
      if (InstrumenterConfig.get().isLegacyContextManagerEnabled()) {
        closePrevious(true);
      } else if (message == null) { // previous message span was the last
        final AgentSpan previousSpan = AgentSpan.fromContext(Context.root().swap());
        if (previousSpan != null) {
          previousSpan.finishWithEndToEnd();
        }
      }
      if (message != null) {
        AgentSpan queueSpan = null;
        EventBridgeMessage eventBridgeMessage = null;
        boolean dataStreamsEnabled = traceConfig().isDataStreamsEnabled();
        boolean sqsPropagationEnabled = Config.get().isSqsPropagationEnabled();
        if (dataStreamsEnabled || (batchContext == null && sqsPropagationEnabled)) {
          eventBridgeMessage = EventBridgeMessage.tryParseFrom(message);
        }
        AgentSpanContext spanContext = batchContext;
        if (spanContext == null) {
          // first grab any incoming distributed context
          spanContext =
              sqsPropagationEnabled ? extractMessageContext(message, eventBridgeMessage) : null;
          // next add a time-in-queue span for non-legacy SQS traces
          if (TIME_IN_QUEUE_ENABLED) {
            long timeInQueueStart = GETTER.extractTimeInQueueStart(message);
            if (timeInQueueStart > 0) {
              queueSpan =
                  startSpan(
                      COMPONENT_NAME.toString(),
                      SQS_TIME_IN_QUEUE_OPERATION,
                      spanContext,
                      MILLISECONDS.toMicros(timeInQueueStart));
              BROKER_DECORATE.afterStart(queueSpan);
              BROKER_DECORATE.onTimeInQueue(queueSpan, queueUrl, queueName, requestId);
              spanContext = queueSpan.spanContext();
              // The queueSpan will be finished after inner span has been activated to ensure that
              // spans are written out together by TraceStructureWriter when running in strict mode
            }
          }
          // re-use this context for any other messages received in this batch
          batchContext = spanContext;
        } else if (eventBridgeMessage != null && sqsPropagationEnabled) {
          spanContext = extractMessageContext(message, eventBridgeMessage);
        }
        AgentSpan span = startSpan(COMPONENT_NAME.toString(), SQS_INBOUND_OPERATION, spanContext);

        // Only build the DSM tags and context when DSM is on: with it off the checkpoint is a
        // no-op, but the arguments were still built for every message.
        if (traceConfig().isDataStreamsEnabled()) {
          DataStreamsTags tags =
              eventBridgeMessage != null
                  ? eventBridgeMessage.consumeTags()
                  : create("sqs", INBOUND, queueName);
          AgentTracer.get().getDataStreamsMonitoring().setCheckpoint(span, create(tags, 0, 0));
        }

        CONSUMER_DECORATE.afterStart(span);
        CONSUMER_DECORATE.onConsume(span, queueUrl, queueName, requestId);
        if (InstrumenterConfig.get().isLegacyContextManagerEnabled()) {
          activateNext(span);
        } else {
          final AgentSpan previousSpan = AgentSpan.fromContext(span.swap());
          if (previousSpan != null) {
            previousSpan.finishWithEndToEnd();
          }
        }
        if (queueSpan != null) {
          BROKER_DECORATE.beforeFinish(queueSpan);
          queueSpan.finish();
        }
      }
    } catch (Exception e) {
      log.debug("Problem tracing new SQS message span", e);
    }
  }

  @Override
  public void remove() {
    delegate.remove();
  }

  private static AgentSpanContext extractMessageContext(
      Message message, EventBridgeMessage eventBridgeMessage) {
    if (null == eventBridgeMessage) {
      return extractContextAndGetSpanContext(message, GETTER);
    }
    Map<String, ?> datadogAttributes = eventBridgeMessage.datadogAttributes();
    return extractContextAndGetSpanContext(
        message,
        (carrier, classifier) -> {
          GETTER.forEachKey(carrier, classifier);
          DatadogAttributesExtractAdapter.GETTER.forEachKey(datadogAttributes, classifier);
        });
  }
}

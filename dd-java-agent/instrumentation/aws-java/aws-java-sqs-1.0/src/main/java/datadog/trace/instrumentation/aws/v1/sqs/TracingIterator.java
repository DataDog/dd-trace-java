package datadog.trace.instrumentation.aws.v1.sqs;

import static datadog.trace.api.datastreams.DataStreamsContext.create;
import static datadog.trace.api.datastreams.DataStreamsTags.Direction.INBOUND;
import static datadog.trace.api.datastreams.DataStreamsTags.create;
import static datadog.trace.bootstrap.instrumentation.api.AgentPropagation.extractContextAndGetSpanContext;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateNext;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.closePrevious;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.bootstrap.instrumentation.api.URIUtils.urlFileName;
import static datadog.trace.instrumentation.aws.v1.sqs.MessageExtractAdapter.GETTER;
import static datadog.trace.instrumentation.aws.v1.sqs.SqsDecorator.BROKER_DECORATE;
import static datadog.trace.instrumentation.aws.v1.sqs.SqsDecorator.CONSUMER_DECORATE;
import static datadog.trace.instrumentation.aws.v1.sqs.SqsDecorator.SQS_INBOUND_OPERATION;
import static datadog.trace.instrumentation.aws.v1.sqs.SqsDecorator.SQS_TIME_IN_QUEUE_OPERATION;
import static datadog.trace.instrumentation.aws.v1.sqs.SqsDecorator.TIME_IN_QUEUE_ENABLED;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import com.amazonaws.services.sqs.model.Message;
import datadog.trace.api.Config;
import datadog.trace.api.datastreams.DataStreamsTags;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.util.Iterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TracingIterator<L extends Iterator<Message>> implements Iterator<Message> {
  private static final Logger log = LoggerFactory.getLogger(TracingIterator.class);

  protected final L delegate;
  private final String queueUrl;
  private AgentSpanContext batchContext;

  public TracingIterator(L delegate, String queueUrl) {
    this.delegate = delegate;
    this.queueUrl = queueUrl;
  }

  @Override
  public boolean hasNext() {
    boolean moreMessages = delegate.hasNext();
    if (!moreMessages) {
      // no more messages, use this as a signal to close the last iteration scope
      closePrevious(true);
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
      closePrevious(true);
      if (message != null) {
        AgentSpan queueSpan = null;
        if (batchContext == null) {
          // first grab any incoming distributed context
          AgentSpanContext spanContext =
              Config.get().isSqsPropagationEnabled()
                  ? extractContextAndGetSpanContext(message, GETTER)
                  : null;
          // next add a time-in-queue span for non-legacy SQS traces
          if (TIME_IN_QUEUE_ENABLED) {
            long timeInQueueStart = GETTER.extractTimeInQueueStart(message);
            if (timeInQueueStart > 0) {
              queueSpan =
                  startSpan(
                      SQS_TIME_IN_QUEUE_OPERATION,
                      spanContext,
                      MILLISECONDS.toMicros(timeInQueueStart));
              BROKER_DECORATE.afterStart(queueSpan);
              BROKER_DECORATE.onTimeInQueue(queueSpan, queueUrl);
              spanContext = queueSpan.context();
              // The queueSpan will be finished after inner span has been activated to ensure that
              // spans are written out together by TraceStructureWriter when running in strict mode
            }
          }
          // re-use this context for any other messages received in this batch
          batchContext = spanContext;
        }
        AgentSpan span = startSpan(SQS_INBOUND_OPERATION, batchContext);

        DataStreamsTags tags = create("sqs", INBOUND, urlFileName(queueUrl));
        AgentTracer.get().getDataStreamsMonitoring().setCheckpoint(span, create(tags, 0, 0));
        System.out.println("Setting a checkpoint in thread" + Thread.currentThread().getId());

        CONSUMER_DECORATE.afterStart(span);
        CONSUMER_DECORATE.onConsume(span, queueUrl);
        activateNext(span);
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
}

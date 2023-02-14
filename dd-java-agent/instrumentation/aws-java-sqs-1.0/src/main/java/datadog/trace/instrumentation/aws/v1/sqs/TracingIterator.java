package datadog.trace.instrumentation.aws.v1.sqs;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateNext;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.closePrevious;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.propagate;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.aws.v1.sqs.MessageExtractAdapter.GETTER;
import static datadog.trace.instrumentation.aws.v1.sqs.SqsDecorator.AWS_HTTP;
import static datadog.trace.instrumentation.aws.v1.sqs.SqsDecorator.BROKER_DECORATE;
import static datadog.trace.instrumentation.aws.v1.sqs.SqsDecorator.CONSUMER_DECORATE;
import static datadog.trace.instrumentation.aws.v1.sqs.SqsDecorator.SQS_LEGACY_TRACING;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import com.amazonaws.services.sqs.model.Message;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.Iterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TracingIterator<L extends Iterator<Message>> implements Iterator<Message> {
  private static final Logger log = LoggerFactory.getLogger(TracingIterator.class);

  protected final L delegate;

  public TracingIterator(L delegate) {
    this.delegate = delegate;
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
        AgentSpan span, queueSpan = null;
        if (Config.get().isSqsPropagationEnabled()) {
          AgentSpan.Context spanContext = propagate().extract(message, GETTER);
          long timeInQueueStart = GETTER.extractTimeInQueueStart(message);
          if (timeInQueueStart == 0 || SQS_LEGACY_TRACING) {
            span = startSpan(AWS_HTTP, spanContext);
          } else {
            queueSpan = startSpan(AWS_HTTP, spanContext, MILLISECONDS.toMicros(timeInQueueStart));
            BROKER_DECORATE.afterStart(queueSpan);
            BROKER_DECORATE.onTimeInQueue(queueSpan);
            span = startSpan(AWS_HTTP, queueSpan.context());
            BROKER_DECORATE.beforeFinish(queueSpan);
            // The queueSpan will be finished after inner span has been activated to ensure that
            // spans are written out together by TraceStructureWriter when running in strict mode
          }
        } else {
          span = startSpan(AWS_HTTP, null);
        }
        CONSUMER_DECORATE.afterStart(span);
        CONSUMER_DECORATE.onConsume(span);
        activateNext(span);
        if (null != queueSpan) {
          queueSpan.finish();
        }
      }
    } catch (Exception e) {
      log.debug("Error starting new message span", e);
    }
  }

  @Override
  public void remove() {
    delegate.remove();
  }
}

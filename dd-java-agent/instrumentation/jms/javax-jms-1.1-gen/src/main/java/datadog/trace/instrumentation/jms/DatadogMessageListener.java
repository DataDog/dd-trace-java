package datadog.trace.instrumentation.jms;

import static datadog.trace.bootstrap.instrumentation.api.AgentPropagation.extractContextAndGetSpanContext;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.jms.JMSDecorator.BROKER_DECORATE;
import static datadog.trace.instrumentation.jms.JMSDecorator.CONSUMER_DECORATE;
import static datadog.trace.instrumentation.jms.JMSDecorator.JMS_CONSUME;
import static datadog.trace.instrumentation.jms.JMSDecorator.JMS_DELIVER;
import static datadog.trace.instrumentation.jms.JMSDecorator.TIME_IN_QUEUE_ENABLED;
import static datadog.trace.instrumentation.jms.MessageExtractAdapter.GETTER;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.jms.MessageConsumerState;
import datadog.trace.bootstrap.instrumentation.jms.SessionState;
import javax.jms.Message;
import javax.jms.MessageListener;

public class DatadogMessageListener implements MessageListener {

  private final ContextStore<Message, SessionState> messageAckStore;
  private final MessageConsumerState consumerState;
  private final MessageListener messageListener;

  public DatadogMessageListener(
      ContextStore<Message, SessionState> messageAckStore,
      MessageConsumerState consumerState,
      MessageListener messageListener) {
    this.messageAckStore = messageAckStore;
    this.consumerState = consumerState;
    this.messageListener = messageListener;
  }

  @Override
  public void onMessage(Message message) {
    AgentSpan span;
    AgentSpanContext propagatedContext = null;
    if (!consumerState.isPropagationDisabled()) {
      propagatedContext = extractContextAndGetSpanContext(message, GETTER);
    }
    long startMillis = GETTER.extractTimeInQueueStart(message);
    if (startMillis == 0 || !TIME_IN_QUEUE_ENABLED) {
      span = startSpan(JMS_CONSUME, propagatedContext);
    } else {
      long batchId = GETTER.extractMessageBatchId(message);
      AgentSpan timeInQueue = consumerState.getTimeInQueueSpan(batchId);
      if (null == timeInQueue) {
        timeInQueue = startSpan(JMS_DELIVER, propagatedContext, MILLISECONDS.toMicros(startMillis));
        BROKER_DECORATE.afterStart(timeInQueue);
        BROKER_DECORATE.onTimeInQueue(
            timeInQueue,
            consumerState.getBrokerResourceName(),
            consumerState.getBrokerServiceName());
        consumerState.setTimeInQueueSpan(batchId, timeInQueue);
      }
      span = startSpan(JMS_CONSUME, timeInQueue.context());
    }
    CONSUMER_DECORATE.afterStart(span);
    CONSUMER_DECORATE.onConsume(span, message, consumerState.getConsumerResourceName());
    SessionState sessionState = consumerState.getSessionState();
    if (sessionState.isClientAcknowledge()) {
      // consumed spans will be finished by a call to Message.acknowledge
      sessionState.finishOnAcknowledge(span);
      messageAckStore.put(message, sessionState);
    } else if (sessionState.isTransactedSession()) {
      // span will be finished by Session.commit/rollback/close
      sessionState.finishOnCommit(span);
    }
    try (AgentScope scope = activateSpan(span)) {
      messageListener.onMessage(message);
    } catch (RuntimeException | Error thrown) {
      CONSUMER_DECORATE.onError(span, thrown);
      throw thrown;
    } finally {
      if (sessionState.isAutoAcknowledge()) {
        span.finish();
        consumerState.finishTimeInQueueSpan(false);
      }
    }
  }
}

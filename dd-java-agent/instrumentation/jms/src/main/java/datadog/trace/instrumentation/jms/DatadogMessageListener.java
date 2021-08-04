package datadog.trace.instrumentation.jms;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.propagate;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.jms.JMSDecorator.CONSUMER_DECORATE;
import static datadog.trace.instrumentation.jms.JMSDecorator.JMS_CONSUME;
import static datadog.trace.instrumentation.jms.MessageExtractAdapter.GETTER;

import datadog.trace.api.Config;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.jms.MessageConsumerState;
import javax.jms.Message;
import javax.jms.MessageListener;

public class DatadogMessageListener implements MessageListener {

  private final ContextStore<Message, AgentSpan> messageAckStore;
  private final MessageConsumerState messageConsumerState;
  private final MessageListener messageListener;

  public DatadogMessageListener(
      ContextStore<Message, AgentSpan> messageAckStore,
      MessageListener messageListener,
      MessageConsumerState messageConsumerState) {
    this.messageAckStore = messageAckStore;
    this.messageConsumerState = messageConsumerState;
    this.messageListener = messageListener;
  }

  @Override
  public void onMessage(Message message) {
    AgentSpan span;
    String destinationName = messageConsumerState.getDestinationName();
    if (!Config.get().isJMSPropagationDisabledForDestination(destinationName)) {
      AgentSpan.Context extractedContext = propagate().extract(message, GETTER);
      span = startSpan(JMS_CONSUME, extractedContext);
    } else {
      span = startSpan(JMS_CONSUME, null);
    }
    CONSUMER_DECORATE.afterStart(span);
    CONSUMER_DECORATE.onConsume(span, message, messageConsumerState.getResourceName());
    try (AgentScope scope = activateSpan(span)) {
      messageListener.onMessage(message);
    } catch (RuntimeException | Error thrown) {
      CONSUMER_DECORATE.onError(span, thrown);
      throw thrown;
    } finally {
      if (messageConsumerState.isClientAcknowledge()) {
        // span will be finished by a call to Message.acknowledge
        messageAckStore.put(message, span);
      } else if (messageConsumerState.isAutoAcknowledge()) {
        span.finish();
      } else if (messageConsumerState.isTransactedSession()) {
        // span will be finished by Session.commit/rollback/close
        messageConsumerState.getSessionState().finishOnCommit(span);
      }
    }
  }
}

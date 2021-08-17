package datadog.trace.instrumentation.jms;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.propagate;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.jms.JMSDecorator.CONSUMER_DECORATE;
import static datadog.trace.instrumentation.jms.JMSDecorator.JMS_CONSUME;
import static datadog.trace.instrumentation.jms.MessageExtractAdapter.GETTER;

import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.jms.MessageConsumerState;
import datadog.trace.bootstrap.instrumentation.jms.SessionState;
import javax.jms.Message;
import javax.jms.MessageListener;

public class DatadogMessageListener implements MessageListener {

  private final ContextStore<Message, AgentSpan> contextStore;
  private final SessionState sessionState;
  private final MessageConsumerState messageConsumerState;
  private final MessageListener messageListener;

  public DatadogMessageListener(
      ContextStore<Message, AgentSpan> contextStore,
      MessageListener messageListener,
      MessageConsumerState messageConsumerState,
      SessionState sessionState) {
    this.contextStore = contextStore;
    this.messageConsumerState = messageConsumerState;
    this.sessionState = sessionState;
    this.messageListener = messageListener;
  }

  @Override
  public void onMessage(Message message) {
    AgentSpan.Context extractedContext = propagate().extract(message, GETTER);
    AgentSpan span = startSpan(JMS_CONSUME, extractedContext);
    CONSUMER_DECORATE.afterStart(span);
    CONSUMER_DECORATE.onConsume(span, message, messageConsumerState.getResourceName());
    try (AgentScope scope = activateSpan(span)) {
      messageListener.onMessage(message);
    } catch (RuntimeException | Error thrown) {
      CONSUMER_DECORATE.onError(span, thrown);
      throw thrown;
    } finally {
      if (messageConsumerState.isClientAcknowledge()) {
        contextStore.put(message, span);
      } else if (messageConsumerState.isAutoAcknowledge()) {
        span.finish();
      } else if (messageConsumerState.isTransactedSession()) {
        sessionState.capture(span);
      }
    }
  }
}

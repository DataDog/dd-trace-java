package datadog.trace.instrumentation.pulsar;

import static datadog.trace.bootstrap.instrumentation.api.AgentPropagation.extractContextAndGetSpanContext;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.pulsar.MessageTextMapGetter.GETTER;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.MessageListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MessageListenerWrapper<T> implements MessageListener<T> {
  private static final long serialVersionUID = 1L;
  private static final Logger log = LoggerFactory.getLogger(MessageListenerWrapper.class);

  private final MessageListener<T> delegate;

  public MessageListenerWrapper(MessageListener<T> messageListener) {
    this.delegate = messageListener;
  }

  @Override
  public void received(Consumer<T> consumer, Message<T> message) {
    // process message
    PulsarRequest request = PulsarRequest.create(message);
    AgentSpanContext parentContext = extractContextAndGetSpanContext(request, GETTER);
    // AgentScope extractScope = MessageStore.extract(message);
    if (parentContext == null) {
      log.debug("MessageListenerWrapper extract scope is null");
      this.delegate.received(consumer, message);
      return;
    }

    try {
      AgentScope scope = start(parentContext, message);
      this.delegate.received(consumer, message);
      end(scope, null);
    } catch (Throwable t) {
      log.warn(t.toString());
    }
  }

  public AgentScope start(AgentSpanContext parentContext, Message<T> message) {
    if (log.isDebugEnabled()){
      log.debug("MessageListenerWrapper start");
    }

    String topicName = message.getTopicName();
    UTF8BytesString spanName = UTF8BytesString.create(topicName + " process");
    AgentSpan span = startSpan(spanName, parentContext);
    span.setResourceName(spanName);
    span.setTag("topic", message.getTopicName());
    span.setTag("destination", message.getTopicName());

    span.setSpanType("queue");
    span.setTag("message_id", message.getMessageId());
    span.setServiceName("pulsar");

    return activateSpan(span);
  }

  public void end(AgentScope scope, Throwable throwable) {
    // todo error
    if (throwable != null) {
      scope.span().setError(true);
      scope.span().setErrorMessage(throwable.getMessage());
    }

    scope.span().finish();
    scope.close();
  }

  @Override
  public void reachedEndOfTopic(Consumer<T> consumer) {
    this.delegate.reachedEndOfTopic(consumer);
  }
}

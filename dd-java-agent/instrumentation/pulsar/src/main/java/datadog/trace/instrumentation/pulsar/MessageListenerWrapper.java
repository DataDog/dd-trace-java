package datadog.trace.instrumentation.pulsar;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.MessageListener;

public  class MessageListenerWrapper<T> implements MessageListener<T> {
  private static final long serialVersionUID = 1L;

  private final MessageListener<T> delegate;

  public MessageListenerWrapper(MessageListener<T> messageListener) {
    this.delegate = messageListener;
  }

  @Override
  public void received(Consumer<T> consumer, Message<T> message) {
      /*      Context parent = VirtualFieldStore.extract(message);

      Instrumenter<PulsarRequest, Void> instrumenter = consumerProcessInstrumenter();
      PulsarRequest request = PulsarRequest.create(message);
      if (!instrumenter.shouldStart(parent, request)) {
        this.delegate.received(consumer, message);
        return;
      }

      Context current = instrumenter.start(parent, request);
      try (Scope scope = current.makeCurrent()) {
        this.delegate.received(consumer, message);
        instrumenter.end(current, request, null, null);
      } catch (Throwable t) {
        instrumenter.end(current, request, null, t);
        throw t;
      }*/

    // process message
    AgentScope extractScope = MessageStore.extract(message);
    if (extractScope == null) {
      System.out.println("scope is null ---------------");
      this.delegate.received(consumer, message);
      return;
    }

    try{
      System.out.println("scope is not null -----------");
      AgentScope scope = start(extractScope, message);
      this.delegate.received(consumer, message);
      end(scope,null);
    }catch (Throwable t){

    }
  }

  public AgentScope start(AgentScope extractScope, Message<T> message) {
    String topicName = message.getTopicName();
    UTF8BytesString spanName = UTF8BytesString.create(topicName + " process");
    AgentSpan span = startSpan("datadog",spanName, extractScope.span().context());
    span.setResourceName(spanName);
    span.setTag("topic", message.getTopicName());
    span.setTag("destination", message.getTopicName());

    //span.setTag(MESSAGING_SYSTEM, LOCAL_SERVICE_NAME);
    span.setSpanType("queue");
    span.setTag("message_id", message.getMessageId());
    span.setServiceName("pulsar");

    AgentScope scope = activateSpan(span);

    return scope;
  }

  public void end(AgentScope scope,Throwable throwable) {
    // todo error
    if (throwable != null) {
      scope.span().setError(true);
      scope.span().setErrorMessage(throwable.getMessage());
    }

    // beforeFinish(scope);
    scope.span().finish();
    scope.close();
  }

  @Override
  public void reachedEndOfTopic(Consumer<T> consumer) {
    this.delegate.reachedEndOfTopic(consumer);
  }
}

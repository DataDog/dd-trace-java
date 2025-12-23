package datadog.trace.instrumentation.googlepubsub;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.instrumentation.googlepubsub.PubSubDecorator.CONSUMER_DECORATE;

import com.google.cloud.pubsub.v1.AckReplyConsumer;
import com.google.cloud.pubsub.v1.MessageReceiver;
import com.google.pubsub.v1.PubsubMessage;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;

public final class MessageReceiverWrapper implements MessageReceiver {
  private final String subscription;
  private final MessageReceiver delegate;

  public MessageReceiverWrapper(String subscription, MessageReceiver delegate) {
    this.subscription = subscription;
    this.delegate = delegate;
  }

  @Override
  public void receiveMessage(PubsubMessage message, AckReplyConsumer consumer) {
    final AgentSpan span = CONSUMER_DECORATE.onConsume(message, subscription);
    try (final AgentScope scope = activateSpan(span)) {
      this.delegate.receiveMessage(message, consumer);
    } finally {
      CONSUMER_DECORATE.beforeFinish(span);
      span.finish();
    }
  }
}

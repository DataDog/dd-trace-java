package datadog.trace.instrumentation.pulsar;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import java.util.concurrent.CompletableFuture;
import org.apache.pulsar.client.api.MessageId;
import org.apache.pulsar.client.impl.MessageImpl;
import org.apache.pulsar.client.impl.SendCallback;

public class SendCallbackWrapper implements SendCallback {

  private final AgentScope scope;
  private final PulsarRequest request;
  private final SendCallback delegate;

  public  SendCallbackWrapper(AgentScope scope, PulsarRequest request, SendCallback callback) {
    this.scope = scope;
    this.request = request;
    this.delegate = callback;
  }

  @Override
  public void sendComplete(Exception e) {
    if (scope == null) {
      this.delegate.sendComplete(e);
      return;
    }

    try {
      this.delegate.sendComplete(e);
    } finally {
      new ProducerDecorator().end(scope, request, e);
    }
  }

  @Override
  public void addCallback(MessageImpl<?> msg, SendCallback scb) {
    this.delegate.addCallback(msg, scb);
  }

  @Override
  public SendCallback getNextSendCallback() {
    return this.delegate.getNextSendCallback();
  }

  @Override
  public MessageImpl<?> getNextMessage() {
    return this.delegate.getNextMessage();
  }

  @Override
  public CompletableFuture<MessageId> getFuture() {
    return this.delegate.getFuture();
  }
}

package datadog.trace.instrumentation.rocketmq5;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import org.apache.rocketmq.client.java.impl.producer.SendReceiptImpl;
import org.apache.rocketmq.client.java.message.PublishingMessageImpl;
import org.apache.rocketmq.shaded.com.google.common.util.concurrent.FutureCallback;

public class SendSpanFinishingCallback implements FutureCallback<SendReceiptImpl> {

  private final AgentScope scope;

  private final PublishingMessageImpl message;

  public SendSpanFinishingCallback(AgentScope scope, PublishingMessageImpl message){
    this.message = message;
    this.scope = scope;
  }
  @Override
  public void onSuccess(SendReceiptImpl result) {
    scope.span().setTag("MessageID",result.getMessageId());
    scope.span().finish();
  }

  @Override
  public void onFailure(Throwable t) {
    scope.span().setTag("Message_Type",message.getMessageType());
    scope.span().setErrorMessage(t.getMessage());
    scope.span().setError(true);
    scope.span().finish();
  }
}

package datadog.trace.instrumentation.rocketmq5;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import org.apache.rocketmq.client.java.impl.producer.SendReceiptImpl;
import org.apache.rocketmq.client.java.message.PublishingMessageImpl;
import org.apache.rocketmq.shaded.com.google.common.util.concurrent.FutureCallback;

public class SendSpanFinishingCallback implements FutureCallback<SendReceiptImpl> {

  private final AgentSpan scope;

  private final PublishingMessageImpl message;

  public SendSpanFinishingCallback(AgentSpan scope, PublishingMessageImpl message){
    this.message = message;
    this.scope = scope;
  }
  @Override
  public void onSuccess(SendReceiptImpl result) {
    scope.setTag("MessageID",result.getMessageId());
    scope.setSpanType("rocketmq");
    scope.finish();
  }

  @Override
  public void onFailure(Throwable t) {
    scope.setTag("Message_Type",message.getMessageType());
    scope.setErrorMessage(t.getMessage());
    scope.setError(true);
    scope.finish();
  }
}

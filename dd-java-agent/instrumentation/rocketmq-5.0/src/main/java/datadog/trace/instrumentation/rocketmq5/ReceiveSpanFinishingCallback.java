package datadog.trace.instrumentation.rocketmq5;

import apache.rocketmq.v2.ReceiveMessageRequest;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import org.apache.rocketmq.client.java.impl.consumer.ReceiveMessageResult;
import org.apache.rocketmq.client.java.message.MessageViewImpl;
import org.apache.rocketmq.shaded.com.google.common.util.concurrent.FutureCallback;

import java.util.List;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.*;
import static datadog.trace.instrumentation.rocketmq5.MessageViewGetter.GetterView;

public class ReceiveSpanFinishingCallback implements FutureCallback<ReceiveMessageResult> {

  private final ReceiveMessageRequest request;
  private final Timer timer;

  public ReceiveSpanFinishingCallback(ReceiveMessageRequest request, Timer timer) {
    this.request = request;
    this.timer = timer;
  }

  @Override
  public void onSuccess(ReceiveMessageResult receiveMessageResult) {
    List<MessageViewImpl> messageViews = receiveMessageResult.getMessageViewImpls();
    // Don't create spans when no messages were received.
    if (messageViews.isEmpty()) {
      return;
    }
    String consumerGroup = request.getGroup().getName();
    String topic = request.getMessageQueue().getTopic().getName();

    for (MessageViewImpl messageView : messageViews) {
    //  propagate().inject(span.context(),messageView,setterView);
      AgentSpan.Context parentContext = propagate().extract(messageView,GetterView);
      if (null != parentContext){
        AgentSpan childSpan = startSpan("receive_message",parentContext);
        AgentScope scopeC = activateSpan(childSpan);
        childSpan.setTag("messageID",messageView.getMessageId());
        scopeC.span().setTag("groupID",consumerGroup);
        scopeC.span().setTag("topic",topic);
        scopeC.span().setTag("status","success");
        scopeC.close();
        scopeC.span().finish();
      }
    }
  }

  @Override
  public void onFailure(Throwable throwable) {
  }
}

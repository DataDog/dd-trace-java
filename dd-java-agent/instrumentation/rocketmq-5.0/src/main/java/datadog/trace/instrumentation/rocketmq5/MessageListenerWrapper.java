package datadog.trace.instrumentation.rocketmq5;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import org.apache.rocketmq.client.apis.consumer.ConsumeResult;
import org.apache.rocketmq.client.apis.consumer.MessageListener;
import org.apache.rocketmq.client.apis.message.MessageView;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.*;
import static datadog.trace.instrumentation.rocketmq5.MessageViewGetter.GetterView;

public class MessageListenerWrapper implements MessageListener {
  private final MessageListener delegator;

  public MessageListenerWrapper(MessageListener delegator) {
    this.delegator = delegator;
  }

  @Override
  public ConsumeResult consume(MessageView messageView) {
    ConsumeResult consumeResult = null;
    // todo  start span and end
    AgentSpan.Context parentContext =propagate().extract(messageView, GetterView);

    AgentSpan span ;
    if (null != parentContext){
      span = startSpan("messageListener",parentContext);
    }else {
     span =  startSpan("messageListener");
    }
    span.setSpanType("rocketmq");
    span.setTag("messageID",messageView.getMessageId());
    span.setServiceName("rocketmq-consume");
    span.setTag("topic",messageView.getTopic());
    span.setTag("tag",messageView.getTag());
    span.setTag("keys",messageView.getKeys());
    span.setTag("message_group",messageView.getMessageGroup());
    AgentScope scope = activateSpan(span);
    consumeResult = delegator.consume(messageView);
    scope.close();
    scope.span().finish();

    return consumeResult;
  }
}

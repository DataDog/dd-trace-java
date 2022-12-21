package datadog.trace.instrumentation.rocketmq5;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import org.apache.rocketmq.client.apis.consumer.ConsumeResult;
import org.apache.rocketmq.client.apis.consumer.MessageListener;
import org.apache.rocketmq.client.apis.message.MessageView;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.propagate;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
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
    // get context 获取context 从message中。 (getter)?
    AgentSpan span ;
    if (null == parentContext){
      span = startSpan("messageListener");
    }else {
      span = startSpan("messageListener",parentContext);
    }

    span.setTag("messageID",messageView.getMessageId());
    consumeResult = delegator.consume(messageView);
    span.finish();
    return consumeResult;
  }
}

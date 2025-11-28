package datadog.trace.instrumentation.rocketmq;


import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import org.apache.rocketmq.client.hook.ConsumeMessageContext;
import org.apache.rocketmq.client.hook.ConsumeMessageHook;

import static datadog.trace.instrumentation.rocketmq.RocketMqDecorator.CONSUMER_DECORATE;

public final class TracingConsumeMessageHookImpl implements ConsumeMessageHook {
  private final RocketMqDecorator rocketMqDecorator;


  TracingConsumeMessageHookImpl() {
    this.rocketMqDecorator = CONSUMER_DECORATE;
  }
  private AgentScope scope;
  @Override
  public String hookName() {
    return "ConsumeMessageTraceHook";
  }

  @Override
  public void consumeMessageBefore(ConsumeMessageContext context) {
    if (context == null || context.getMsgList() == null || context.getMsgList().isEmpty()) {
      return;
    }
    scope = rocketMqDecorator.start(context);
  }

  @Override
  public void consumeMessageAfter(ConsumeMessageContext context) {
    if (context == null || context.getMsgList() == null || context.getMsgList().isEmpty()) {
      return;
    }
      rocketMqDecorator.end(context,scope);
  }
}


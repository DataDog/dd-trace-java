package datadog.trace.instrumentation.rocketmq;


import org.apache.rocketmq.client.hook.ConsumeMessageContext;
import org.apache.rocketmq.client.hook.ConsumeMessageHook;

import static datadog.trace.instrumentation.rocketmq.RocketMqDecorator.CONSUMER_DECORATE;

public final class TracingConsumeMessageHookImpl implements ConsumeMessageHook {
  private final RocketMqDecorator rocketMqDecorator;
  //private final ContextStore<ConsumeMessageContext,AgentScope> scopeAccessor;

  TracingConsumeMessageHookImpl() {
    this.rocketMqDecorator = CONSUMER_DECORATE;
 //   this.scopeAccessor = scopeAccessor;
  }

  @Override
  public String hookName() {
    return "ConsumeMessageTraceHook";
  }

  @Override
  public void consumeMessageBefore(ConsumeMessageContext context) {
    if (context == null || context.getMsgList() == null || context.getMsgList().isEmpty()) {
      return;
    }
     rocketMqDecorator.start(context);
  }

  @Override
  public void consumeMessageAfter(ConsumeMessageContext context) {
    if (context == null || context.getMsgList() == null || context.getMsgList().isEmpty()) {
      return;
    }
      rocketMqDecorator.end(context);
  }
}


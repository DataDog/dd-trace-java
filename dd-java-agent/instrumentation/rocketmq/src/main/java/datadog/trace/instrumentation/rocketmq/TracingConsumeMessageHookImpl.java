package datadog.trace.instrumentation.rocketmq;

import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import org.apache.rocketmq.client.hook.ConsumeMessageContext;
import org.apache.rocketmq.client.hook.ConsumeMessageHook;

public final class TracingConsumeMessageHookImpl implements ConsumeMessageHook {
  private final RocketMqDecorator rocketMqDecorator;
  //private final ContextStore<ConsumeMessageContext,AgentScope> scopeAccessor;

  TracingConsumeMessageHookImpl() {
    this.rocketMqDecorator = new RocketMqDecorator();
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


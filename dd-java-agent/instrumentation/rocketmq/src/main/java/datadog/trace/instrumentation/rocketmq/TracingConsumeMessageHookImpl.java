package datadog.trace.instrumentation.rocketmq;

import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import org.apache.rocketmq.client.hook.ConsumeMessageContext;
import org.apache.rocketmq.client.hook.ConsumeMessageHook;

public final class TracingConsumeMessageHookImpl implements ConsumeMessageHook {
  private final RocketMqDecorator rocketMqDecorator;
  private final ContextStore<ConsumeMessageContext,AgentScope> scopeAccessor;

  TracingConsumeMessageHookImpl(ContextStore<ConsumeMessageContext,AgentScope> scopeAccessor) {
    this.rocketMqDecorator = new RocketMqDecorator();
    this.scopeAccessor = scopeAccessor;
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
    AgentScope scope = rocketMqDecorator.start(context);
   // System.out.println("start Span  and put to ContextStore");
    scopeAccessor.put(context,scope);
  }

  @Override
  public void consumeMessageAfter(ConsumeMessageContext context) {
    if (context == null || context.getMsgList() == null || context.getMsgList().isEmpty()) {
      return;
    }
    AgentScope scope = scopeAccessor.get(context);
    if (scope!=null){
      rocketMqDecorator.end(context, scope);
    }
  }
}


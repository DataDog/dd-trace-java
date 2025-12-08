package datadog.trace.instrumentation.rocketmq;

import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import org.apache.rocketmq.client.hook.ConsumeMessageContext;
import org.apache.rocketmq.client.hook.ConsumeMessageHook;

import static datadog.trace.instrumentation.rocketmq.RocketMqDecorator.CONSUMER_DECORATE;

public final class TracingConsumeMessageHookImpl implements ConsumeMessageHook {
  private final RocketMqDecorator rocketMqDecorator;

  TracingConsumeMessageHookImpl(ContextStore<ConsumeMessageContext, AgentScope> store) {
    this.store = store;
    this.rocketMqDecorator = CONSUMER_DECORATE;
  }

  // private AgentScope scope;

  private final ContextStore<ConsumeMessageContext, AgentScope> store;

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
    store.put(context, scope);
  }

  @Override
  public void consumeMessageAfter(ConsumeMessageContext context) {
    AgentScope scope = store.get(context);

    if (scope == null) {
      return;
    }
    if (context == null || context.getMsgList() == null || context.getMsgList().isEmpty()) {
      scope.close();
      return;
    }

    rocketMqDecorator.end(context, scope);
    scope.close();
  }
}

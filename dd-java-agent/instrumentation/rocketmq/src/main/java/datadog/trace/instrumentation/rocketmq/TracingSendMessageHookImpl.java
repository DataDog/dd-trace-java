package datadog.trace.instrumentation.rocketmq;

import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import org.apache.rocketmq.client.hook.SendMessageContext;
import org.apache.rocketmq.client.hook.SendMessageHook;

public final class TracingSendMessageHookImpl implements SendMessageHook {

  private final RocketMqDecorator rocketMqDecorator;
  private final ContextStore<SendMessageContext,AgentScope> scopeAccessor;

  TracingSendMessageHookImpl(ContextStore<SendMessageContext,AgentScope> scopeAccessor) {
    this.rocketMqDecorator = new RocketMqDecorator();
    this.scopeAccessor = scopeAccessor;
  }

  @Override
  public String hookName() {
    return "SendMessageTraceHook";
  }

  @Override
  public void sendMessageBefore(SendMessageContext context) {
    if (context == null) {
      return;
    }
    AgentScope scope = rocketMqDecorator.start(context);
    scopeAccessor.put(context,scope);

  }

  @Override
  public void sendMessageAfter(SendMessageContext context) {
    if (context == null) {
      return;
    }
    AgentScope scope = scopeAccessor.get(context);
    if (scope != null) {
      rocketMqDecorator.end(context, scope);
    }
  }
}

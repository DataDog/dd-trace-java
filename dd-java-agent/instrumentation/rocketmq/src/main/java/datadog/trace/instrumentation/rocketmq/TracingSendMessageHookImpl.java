package datadog.trace.instrumentation.rocketmq;

import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import org.apache.rocketmq.client.hook.SendMessageContext;
import org.apache.rocketmq.client.hook.SendMessageHook;

public final class TracingSendMessageHookImpl implements SendMessageHook {

  private final RocketMqDecorator rocketMqDecorator;
 // private final ContextStore<SendMessageContext,AgentScope> scopeAccessor;

  private AgentScope scope;

  TracingSendMessageHookImpl() {
    this.rocketMqDecorator = new RocketMqDecorator();
   // this.scopeAccessor = scopeAccessor;
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
     rocketMqDecorator.start(context);
  }

  @Override
  public void sendMessageAfter(SendMessageContext context) {
    if (context == null) {
      return;
    }
      rocketMqDecorator.end(context);
  }
}

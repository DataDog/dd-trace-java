package datadog.trace.instrumentation.rocketmq;

import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import org.apache.rocketmq.client.hook.SendMessageContext;
import org.apache.rocketmq.client.hook.SendMessageHook;

import static datadog.trace.instrumentation.rocketmq.RocketMqDecorator.PRODUCER_DECORATE;

public final class TracingSendMessageHookImpl implements SendMessageHook {

  private final RocketMqDecorator rocketMqDecorator;

  private final ContextStore<SendMessageContext, AgentScope> store;

  TracingSendMessageHookImpl(ContextStore<SendMessageContext, AgentScope> store) {
    this.rocketMqDecorator = PRODUCER_DECORATE;
    this.store = store;
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
    store.put(context, scope);
  }

  @Override
  public void sendMessageAfter(SendMessageContext context) {
    AgentScope scope = store.get(context);
    if (scope == null) {
      return;
    }
    if (context == null) {
      scope.close();
      return;
    }
    rocketMqDecorator.end(context, scope);
    scope.close();
  }
}

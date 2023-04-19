package datadog.trace.instrumentation.rocketmq;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import org.apache.rocketmq.client.hook.SendMessageContext;
import org.apache.rocketmq.client.hook.SendMessageHook;

final class TracingSendMessageHookImpl implements SendMessageHook {

  private final RocketMqDecorator rocketMqDecorator;

  TracingSendMessageHookImpl() {
    this.rocketMqDecorator = new RocketMqDecorator();
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
    Object o = context.getMqTraceContext();
    if( o == null){
      context.setMqTraceContext(scope);
    }
  }

  @Override
  public void sendMessageAfter(SendMessageContext context) {
    if (context == null) {
      return;
    }
    AgentScope scope = (AgentScope) context.getMqTraceContext();
    if (scope != null) {
      rocketMqDecorator.end(context, scope);
    }
  }
}

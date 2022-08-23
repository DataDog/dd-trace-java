package datadog.trace.instrumentation.rocketmq;


import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import org.apache.rocketmq.client.hook.ConsumeMessageContext;
import org.apache.rocketmq.client.hook.ConsumeMessageHook;

final class TracingConsumeMessageHookImpl implements ConsumeMessageHook {

  private final RocketMqDecorator rocketMqDecorator;

  TracingConsumeMessageHookImpl() {
    this.rocketMqDecorator = new RocketMqDecorator();
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

    context.setMqTraceContext(scope);
  }

  @Override
  public void consumeMessageAfter(ConsumeMessageContext context) {
    if (context == null || context.getMsgList() == null || context.getMsgList().isEmpty()) {
      return;
    }
    AgentScope scope = (AgentScope) context.getMqTraceContext();
    rocketMqDecorator.end(context, scope);
  }
}


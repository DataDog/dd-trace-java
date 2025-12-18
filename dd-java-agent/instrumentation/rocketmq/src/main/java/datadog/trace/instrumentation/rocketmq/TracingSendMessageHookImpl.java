package datadog.trace.instrumentation.rocketmq;

import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import org.apache.rocketmq.client.hook.SendMessageContext;
import org.apache.rocketmq.client.hook.SendMessageHook;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;

import static datadog.trace.instrumentation.rocketmq.RocketMqDecorator.PRODUCER_DECORATE;

public final class TracingSendMessageHookImpl implements SendMessageHook {

  private final RocketMqDecorator rocketMqDecorator;
  private static final Logger log = LoggerFactory.getLogger(TracingSendMessageHookImpl.class);
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
    AgentScope scope = store.get(context);
    if (scope == null){
      scope = rocketMqDecorator.start(context);
      store.putIfAbsent(context, scope);
    }
  }

  @Override
  public void sendMessageAfter(SendMessageContext context) {
    AgentScope scope = store.get(context);
    if (scope == null) {
      return;
    }

    rocketMqDecorator.end(context, scope);
    scope.close();
    if (log.isDebugEnabled()) {
      log.debug("scope close");
    }
  }
}

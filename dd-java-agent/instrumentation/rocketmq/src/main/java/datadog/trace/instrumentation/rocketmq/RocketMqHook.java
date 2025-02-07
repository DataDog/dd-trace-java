package datadog.trace.instrumentation.rocketmq;

import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import org.apache.rocketmq.client.hook.ConsumeMessageContext;
import org.apache.rocketmq.client.hook.ConsumeMessageHook;
import org.apache.rocketmq.client.hook.SendMessageContext;
import org.apache.rocketmq.client.hook.SendMessageHook;

public final class RocketMqHook {
  public static ConsumeMessageHook buildConsumerHook(){
    return new TracingConsumeMessageHookImpl();
  }
  public static SendMessageHook buildSendHook(){
      return new TracingSendMessageHookImpl();
  }
}

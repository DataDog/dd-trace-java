package datadog.trace.instrumentation.rocketmq;

import org.apache.rocketmq.client.hook.ConsumeMessageHook;
import org.apache.rocketmq.client.hook.SendMessageHook;

public final class RocketMqHook {
  public static final ConsumeMessageHook CONSUME_MESSAGE_HOOK = new TracingConsumeMessageHookImpl();

  public static final SendMessageHook SEND_MESSAGE_HOOK = new TracingSendMessageHookImpl();
}

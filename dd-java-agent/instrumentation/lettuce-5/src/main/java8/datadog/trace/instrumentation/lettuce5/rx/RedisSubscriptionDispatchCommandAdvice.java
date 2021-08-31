package datadog.trace.instrumentation.lettuce5.rx;

import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.lettuce.core.protocol.RedisCommand;
import net.bytebuddy.asm.Advice;

public class RedisSubscriptionDispatchCommandAdvice {
  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static void beforeDispatch(
      @Advice.FieldValue("subscriptionCommand") RedisCommand subscriptionCommand) {

    AgentSpan span =
        InstrumentationContext.get(RedisCommand.class, AgentSpan.class).get(subscriptionCommand);
    if (span != null) {
      span.startThreadMigration();
    }
  }
}

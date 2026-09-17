package datadog.trace.instrumentation.lettuce5.rx;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;

import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.lettuce.core.protocol.RedisCommand;
import net.bytebuddy.asm.Advice;

public class RedisSubscriptionDispatchAdvice {

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static AgentScope beforeDispatch(
      @Advice.FieldValue("subscriptionCommand") RedisCommand subscriptionCommand) {
    AgentSpan span =
        InstrumentationContext.get(RedisCommand.class, AgentSpan.class).get(subscriptionCommand);
    return span != null ? activateSpan(span) : null;
  }

  @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
  public static void afterDispatch(@Advice.Enter AgentScope scope) {
    if (scope != null) {
      scope.close();
    }
  }
}

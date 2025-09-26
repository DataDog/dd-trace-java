package datadog.trace.instrumentation.lettuce5.rx;

import static datadog.trace.instrumentation.lettuce5.LettuceClientDecorator.DECORATE;

import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.lettuce.core.protocol.RedisCommand;
import net.bytebuddy.asm.Advice;

public class RedisSubscriptionCommandErrorAdvice {
  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void afterError(
      @Advice.This RedisCommand command, @Advice.Argument(value = 0) Throwable throwable) {

    ContextStore<RedisCommand, AgentSpan> ctx =
        InstrumentationContext.get(RedisCommand.class, AgentSpan.class);
    AgentSpan span = ctx.get(command);
    if (span != null) {
      DECORATE.onError(span, throwable);
      DECORATE.beforeFinish(span);
      span.finish();
    }
    ctx.put(command, null);
  }
}

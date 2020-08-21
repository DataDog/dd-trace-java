package datadog.trace.instrumentation.lettuce4;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.lettuce4.LettuceClientDecorator.DECORATE;

import com.lambdaworks.redis.protocol.AsyncCommand;
import com.lambdaworks.redis.protocol.RedisCommand;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;

public class LettuceAsyncCommandsAdvice {

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static AgentScope onEnter(
      @Advice.Argument(0) final RedisCommand<?, ?, ?> command,
      @Advice.Origin("#t") final String originType,
      @Advice.Origin("#m") final String originMethod) {
    final AgentSpan span = startSpan("redis.query");
    DECORATE.afterStart(span);
    DECORATE.onCommand(span, command);
    return activateSpan(span, originType, originMethod);
  }

  @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
  public static void onExit(
      @Advice.Argument(0) final RedisCommand<?, ?, ?> command,
      @Advice.Enter final AgentScope scope,
      @Advice.Thrown final Throwable throwable,
      @Advice.Return final AsyncCommand<?, ?, ?> asyncCommand) {
    InstrumentationPoints.afterCommand(command, scope, throwable, asyncCommand);
  }
}

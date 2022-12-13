package datadog.trace.instrumentation.lettuce5;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.lettuce5.LettuceClientDecorator.DECORATE;
import static datadog.trace.instrumentation.lettuce5.LettuceClientDecorator.REDIS_QUERY;
import static datadog.trace.instrumentation.lettuce5.LettuceInstrumentationUtil.expectsResponse;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.lettuce.core.protocol.AsyncCommand;
import io.lettuce.core.protocol.RedisCommand;
import net.bytebuddy.asm.Advice;

public class LettuceAsyncCommandsAdvice {

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static AgentScope onEnter(@Advice.Argument(0) final RedisCommand command) {

    final AgentSpan span = startSpan(REDIS_QUERY);
    DECORATE.afterStart(span);
    DECORATE.onCommand(span, command);

    return activateSpan(span);
  }

  @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
  public static void stopSpan(
      @Advice.Argument(0) final RedisCommand command,
      @Advice.Enter final AgentScope scope,
      @Advice.Thrown final Throwable throwable,
      @Advice.Return final AsyncCommand<?, ?, ?> asyncCommand) {

    final AgentSpan span = scope.span();
    if (throwable != null) {
      DECORATE.onError(span, throwable);
      DECORATE.beforeFinish(span);
      scope.close();
      span.finish();
      return;
    }

    // close spans on error or normal completion
    if (expectsResponse(command)) {
      asyncCommand.handleAsync(new LettuceAsyncBiFunction<>(span));
    } else {
      DECORATE.beforeFinish(span);
      span.finish();
    }
    scope.close();
    // span may be finished by LettuceAsyncBiFunction
  }
}

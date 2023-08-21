package datadog.trace.instrumentation.lettuce5;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.lettuce5.LettuceClientDecorator.DECORATE;
import static datadog.trace.instrumentation.lettuce5.LettuceInstrumentationUtil.expectsResponse;

import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.lettuce.core.AbstractRedisAsyncCommands;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulConnection;
import io.lettuce.core.protocol.AsyncCommand;
import io.lettuce.core.protocol.RedisCommand;
import net.bytebuddy.asm.Advice;

public class LettuceAsyncCommandsAdvice {

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static AgentScope onEnter(
      @Advice.Argument(0) final RedisCommand command,
      @Advice.This final AbstractRedisAsyncCommands thiz) {

    final AgentSpan span = startSpan(LettuceClientDecorator.OPERATION_NAME);
    DECORATE.afterStart(span);
    DECORATE.onConnection(
        span,
        InstrumentationContext.get(StatefulConnection.class, RedisURI.class)
            .get(thiz.getConnection()));
    DECORATE.onCommand(span, command);

    return activateSpan(span);
  }

  @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
  public static void stopSpan(
      @Advice.Argument(0) final RedisCommand command,
      @Advice.Enter final AgentScope scope,
      @Advice.Thrown final Throwable throwable,
      @Advice.Return AsyncCommand<?, ?, ?> asyncCommand) {

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
      asyncCommand.whenComplete(new LettuceAsyncBiConsumer<>(span));
    } else {
      DECORATE.beforeFinish(span);
      span.finish();
    }
    scope.close();
    // span may be finished by LettuceAsyncBiConsumer
  }
}

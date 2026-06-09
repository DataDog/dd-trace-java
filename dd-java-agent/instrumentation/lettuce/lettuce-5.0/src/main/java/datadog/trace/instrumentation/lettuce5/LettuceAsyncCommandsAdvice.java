package datadog.trace.instrumentation.lettuce5;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.lettuce5.LettuceClientDecorator.DECORATE;
import static datadog.trace.instrumentation.lettuce5.LettuceInstrumentationUtil.disableAsyncPropagation;
import static datadog.trace.instrumentation.lettuce5.LettuceInstrumentationUtil.expectsResponse;
import static datadog.trace.instrumentation.lettuce5.LettuceInstrumentationUtil.restoreAsyncPropagation;

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
      @Advice.This final AbstractRedisAsyncCommands thiz,
      @Advice.Local("commandExpectsResponse") boolean commandExpectsResponse,
      @Advice.Local("restoreAsyncPropagation") boolean restoreAsyncPropagation) {

    final AgentSpan span =
        startSpan(
            LettuceClientDecorator.REDIS_CLIENT.toString(), LettuceClientDecorator.OPERATION_NAME);
    DECORATE.afterStart(span);
    DECORATE.onConnection(
        span,
        InstrumentationContext.get(StatefulConnection.class, RedisURI.class)
            .get(thiz.getConnection()));
    DECORATE.onCommand(span, command);

    final AgentScope scope = activateSpan(span);
    commandExpectsResponse = expectsResponse(command);
    if (!commandExpectsResponse) {
      restoreAsyncPropagation = disableAsyncPropagation();
    }

    return scope;
  }

  @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
  public static void stopSpan(
      @Advice.Enter final AgentScope scope,
      @Advice.Local("commandExpectsResponse") final boolean commandExpectsResponse,
      @Advice.Local("restoreAsyncPropagation") final boolean restoreAsyncPropagation,
      @Advice.Thrown final Throwable throwable,
      @Advice.Return AsyncCommand<?, ?, ?> asyncCommand) {

    try {
      if (scope == null) {
        return;
      }
      final AgentSpan span = scope.span();
      if (throwable != null) {
        DECORATE.onError(span, throwable);
        DECORATE.beforeFinish(span);
        span.finish();
        return;
      }

      // close spans on error or normal completion
      if (commandExpectsResponse) {
        final boolean restoreCompletionCallbackPropagation = disableAsyncPropagation();
        try {
          asyncCommand.whenComplete(new LettuceAsyncBiConsumer<>(span));
        } finally {
          restoreAsyncPropagation(restoreCompletionCallbackPropagation);
        }
      } else {
        DECORATE.beforeFinish(span);
        span.finish();
      }
      // span may be finished by LettuceAsyncBiConsumer
    } finally {
      restoreAsyncPropagation(restoreAsyncPropagation);
      if (scope != null) {
        scope.close();
      }
    }
  }
}

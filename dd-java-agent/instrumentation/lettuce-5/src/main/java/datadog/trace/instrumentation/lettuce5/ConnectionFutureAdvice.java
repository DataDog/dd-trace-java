package datadog.trace.instrumentation.lettuce5;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.lettuce5.LettuceClientDecorator.DECORATE;

import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.lettuce.core.ConnectionFuture;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulConnection;
import net.bytebuddy.asm.Advice;

public class ConnectionFutureAdvice {
  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static AgentScope onEnter(@Advice.Argument(1) final RedisURI redisUri) {
    final AgentSpan span = startSpan(LettuceClientDecorator.OPERATION_NAME);
    DECORATE.afterStart(span);
    span.setResourceName(DECORATE.resourceNameForConnection(redisUri));
    DECORATE.onConnection(span, redisUri);
    return activateSpan(span);
  }

  @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
  public static void stopSpan(
      @Advice.Enter final AgentScope scope,
      @Advice.Thrown final Throwable throwable,
      @Advice.Argument(1) final RedisURI redisUri,
      @Advice.Return(readOnly = false)
          ConnectionFuture<? extends StatefulConnection> connectionFuture) {
    final AgentSpan span = scope.span();
    if (throwable != null) {
      DECORATE.onError(span, throwable);
      DECORATE.beforeFinish(span);
      scope.close();
      span.finish();
      return;
    }
    connectionFuture =
        connectionFuture.whenComplete(
            new ConnectionContextBiConsumer(
                    redisUri, InstrumentationContext.get(StatefulConnection.class, RedisURI.class))
                .andThen(new LettuceAsyncBiConsumer<>(span)));
    scope.close();
    // span finished by LettuceAsyncBiConsumer
  }
}

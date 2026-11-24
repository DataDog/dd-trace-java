package datadog.trace.instrumentation.lettuce5.rx;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.lettuce5.LettuceClientDecorator.DECORATE;
import static datadog.trace.instrumentation.lettuce5.LettuceInstrumentationUtil.expectsResponse;

import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.instrumentation.lettuce5.LettuceClientDecorator;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulConnection;
import io.lettuce.core.protocol.RedisCommand;
import net.bytebuddy.asm.Advice;
import org.reactivestreams.Subscription;

public class RedisSubscriptionSubscribeAdvice {
  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static AgentSpan beforeSubscribe(
      @Advice.This Subscription subscription,
      @Advice.FieldValue("command") RedisCommand command,
      @Advice.FieldValue("subscriptionCommand") RedisCommand subscriptionCommand) {

    RedisSubscriptionState state =
        (RedisSubscriptionState)
            InstrumentationContext.get(
                    "io.lettuce.core.RedisPublisher$RedisSubscription",
                    "datadog.trace.instrumentation.lettuce5.rx.RedisSubscriptionState")
                .get(subscription);
    AgentSpan span =
        startSpan(
            LettuceClientDecorator.REDIS_CLIENT.toString(), LettuceClientDecorator.OPERATION_NAME);
    InstrumentationContext.get(RedisCommand.class, AgentSpan.class).put(subscriptionCommand, span);
    DECORATE.afterStart(span);
    if (state != null && state.connection != null) {
      DECORATE.onConnection(
          span,
          InstrumentationContext.get(StatefulConnection.class, RedisURI.class)
              .get(state.connection));
    }
    DECORATE.onCommand(span, command);

    return span;
  }

  @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
  public static void afterSubscribe(
      @Advice.FieldValue("command") RedisCommand command,
      @Advice.FieldValue("subscriptionCommand") RedisCommand subscriptionCommand,
      @Advice.Enter AgentSpan span,
      @Advice.Thrown Throwable throwable) {
    if (throwable != null || !expectsResponse(command)) {
      DECORATE.onError(span, throwable);
      DECORATE.beforeFinish(span);
      span.finish();
      InstrumentationContext.get(RedisCommand.class, AgentSpan.class)
          .put(subscriptionCommand, null);
    }
  }
}

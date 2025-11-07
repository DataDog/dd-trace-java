package datadog.trace.instrumentation.lettuce5.rx;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.lettuce5.LettuceClientDecorator.DECORATE;
import static datadog.trace.instrumentation.lettuce5.LettuceInstrumentationUtil.expectsResponse;

import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.instrumentation.lettuce5.LettuceClientDecorator;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulConnection;
import io.lettuce.core.protocol.RedisCommand;
import net.bytebuddy.asm.Advice;
import org.reactivestreams.Subscription;

public class RedisSubscriptionSubscribeAdvice {
  public static final class State {
    public final AgentScope parentScope;
    public final AgentSpan span;

    public State(AgentScope parentScope, AgentSpan span) {
      this.parentScope = parentScope;
      this.span = span;
    }
  }

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static State beforeSubscribe(
      @Advice.This Subscription subscription,
      @Advice.FieldValue("command") RedisCommand command,
      @Advice.FieldValue("subscriptionCommand") RedisCommand subscriptionCommand) {

    AgentScope parentScope = null;
    RedisSubscriptionState state =
        (RedisSubscriptionState)
            InstrumentationContext.get(
                    "io.lettuce.core.RedisPublisher$RedisSubscription",
                    "datadog.trace.instrumentation.lettuce5.rx.RedisSubscriptionState")
                .get(subscription);
    AgentSpan parentSpan = state != null ? state.parentSpan : null;
    if (parentSpan != null) {
      parentScope = activateSpan(parentSpan);
    }
    AgentSpan span = startSpan(LettuceClientDecorator.OPERATION_NAME);
    InstrumentationContext.get(RedisCommand.class, AgentSpan.class).put(subscriptionCommand, span);
    DECORATE.afterStart(span);
    if (state != null && state.connection != null) {
      DECORATE.onConnection(
          span,
          InstrumentationContext.get(StatefulConnection.class, RedisURI.class)
              .get(state.connection));
    }
    DECORATE.onCommand(span, command);

    return new State(parentScope, span);
  }

  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void afterSubscribe(
      @Advice.FieldValue("command") RedisCommand command,
      @Advice.FieldValue("subscriptionCommand") RedisCommand subscriptionCommand,
      @Advice.Enter State state) {
    if (!expectsResponse(command)) {
      DECORATE.beforeFinish(state.span);
      state.span.finish();
      InstrumentationContext.get(RedisCommand.class, AgentSpan.class)
          .put(subscriptionCommand, null);
    }
    if (state.parentScope != null) {
      state.parentScope.close();
    }
  }
}

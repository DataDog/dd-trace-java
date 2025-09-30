package datadog.trace.instrumentation.lettuce5.rx;

import static datadog.trace.instrumentation.lettuce5.LettuceClientDecorator.DECORATE;
import static datadog.trace.instrumentation.lettuce5.LettuceInstrumentationUtil.expectsResponse;

import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.lettuce.core.protocol.RedisCommand;
import net.bytebuddy.asm.Advice;
import org.reactivestreams.Subscription;

/** Instrumentation for SubscriptionCommand in version 5.3.6 and later */
public class RedisSubscriptionCommandOnCompleteAdvice {

  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void afterComplete(
      @Advice.This RedisCommand command,
      @Advice.FieldValue("subscription") Subscription subscription) {

    AgentSpan span = InstrumentationContext.get(RedisCommand.class, AgentSpan.class).get(command);

    if (span != null) {
      ContextStore<Subscription, RedisSubscriptionState> store =
          InstrumentationContext.get(
              "io.lettuce.core.RedisPublisher$RedisSubscription",
              "datadog.trace.instrumentation.lettuce5.rx.RedisSubscriptionState");
      RedisSubscriptionState state = store.get(subscription);
      if (state != null) {
        if (state.count > 1) {
          span.setTag("db.command.results.count", state.count);
        }
      }
      if (state != null && state.cancelled) {
        span.setTag("db.command.cancelled", true);
      }
      if (expectsResponse(command)) {
        DECORATE.beforeFinish(span);
        span.finish();
        store.put(subscription, null);
      }
    }
  }
}

package datadog.trace.instrumentation.lettuce5.rx;

import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import io.lettuce.core.protocol.RedisCommand;
import net.bytebuddy.asm.Advice;
import org.reactivestreams.Subscription;

public class RedisSubscriptionAdvanceAdvice {

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static void beforeOnNext(
      @Advice.This Subscription subscription, @Advice.FieldValue("command") RedisCommand command) {

    ContextStore<Subscription, RedisSubscriptionState> store =
        InstrumentationContext.get(
            "io.lettuce.core.RedisPublisher$RedisSubscription",
            "datadog.trace.instrumentation.lettuce5.rx.RedisSubscriptionState");
    RedisSubscriptionState value = store.get(subscription);
    if (value == null) {
      value = new RedisSubscriptionState();
      store.put(subscription, value);
    }
    if (command.isCancelled()) {
      value.cancelled = true;
    }
    if (!value.cancelled) {
      value.count++;
    }
  }
}

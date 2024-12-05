package datadog.trace.instrumentation.lettuce5.rx;

import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import io.lettuce.core.api.StatefulConnection;
import net.bytebuddy.asm.Advice;
import org.reactivestreams.Subscription;

public class RedisSubscriptionConnectionContextAdvice {
  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void afterConstruct(
      @Advice.This final Subscription subscription,
      @Advice.Argument(0) final StatefulConnection connection) {
    final ContextStore<Subscription, RedisSubscriptionState> store =
        InstrumentationContext.get(
            "io.lettuce.core.RedisPublisher$RedisSubscription",
            "datadog.trace.instrumentation.lettuce5.rx.RedisSubscriptionState");
    RedisSubscriptionState value = store.get(subscription);
    if (value == null) {
      value = new RedisSubscriptionState();
      value.connection = connection;
      store.put(subscription, value);
    }
  }
}

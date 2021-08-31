package datadog.trace.instrumentation.lettuce5.rx;

import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.lettuce.core.protocol.RedisCommand;
import net.bytebuddy.asm.Advice;
import org.reactivestreams.Subscription;

public class RedisChannelHandlerAdvice {

  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void afterDispatch(@Advice.Argument(0) RedisCommand command) {

    ContextStore<RedisCommand, Subscription> ctx1 =
        InstrumentationContext.get(RedisCommand.class, Subscription.class);
    Subscription subscription = ctx1 != null ? ctx1.get(command) : null;
    if (subscription != null) {
      ContextStore<Subscription, AgentSpan> ctx2 =
          InstrumentationContext.get(Subscription.class, AgentSpan.class);
      AgentSpan span = ctx2 != null ? ctx2.get(subscription) : null;
      if (span != null) {
        span.startThreadMigration();
      }
    }
  }
}

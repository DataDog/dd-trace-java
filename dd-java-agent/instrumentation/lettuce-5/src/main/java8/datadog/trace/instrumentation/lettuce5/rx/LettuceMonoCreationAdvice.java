package datadog.trace.instrumentation.lettuce5.rx;

import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import io.lettuce.core.protocol.RedisCommand;
import java.util.function.Supplier;
import net.bytebuddy.asm.Advice;
import reactor.core.publisher.Mono;

public class LettuceMonoCreationAdvice {

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static RedisCommand extractCommandName(
      @Advice.Argument(0) final Supplier<RedisCommand> supplier) {
    return supplier.get();
  }

  // throwables wouldn't matter here, because no spans have been started due to redis command not
  // being run until the user subscribes to the Mono publisher
  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void afterCreateMono(@Advice.Return(readOnly = false) Mono<?> publisher) {
    LettuceFlowTracker tracker = new LettuceFlowTracker(AgentTracer.activeSpan());
    publisher = publisher.doOnSubscribe(tracker);
  }
}

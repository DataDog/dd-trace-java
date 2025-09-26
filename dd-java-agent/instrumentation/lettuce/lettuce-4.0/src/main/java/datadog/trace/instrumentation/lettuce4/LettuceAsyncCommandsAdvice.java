package datadog.trace.instrumentation.lettuce4;

import com.lambdaworks.redis.AbstractRedisAsyncCommands;
import com.lambdaworks.redis.RedisURI;
import com.lambdaworks.redis.api.StatefulConnection;
import com.lambdaworks.redis.protocol.AsyncCommand;
import com.lambdaworks.redis.protocol.RedisCommand;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import net.bytebuddy.asm.Advice;

public class LettuceAsyncCommandsAdvice {

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static AgentScope onEnter(
      @Advice.Argument(0) final RedisCommand<?, ?, ?> command,
      @Advice.This AbstractRedisAsyncCommands thiz) {
    return InstrumentationPoints.beforeCommand(
        command,
        InstrumentationContext.get(StatefulConnection.class, RedisURI.class)
            .get(thiz.getConnection()));
  }

  @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
  public static void onExit(
      @Advice.Argument(0) final RedisCommand<?, ?, ?> command,
      @Advice.Enter final AgentScope scope,
      @Advice.Thrown final Throwable throwable,
      @Advice.Return final AsyncCommand<?, ?, ?> asyncCommand) {
    InstrumentationPoints.afterCommand(command, scope, throwable, asyncCommand);
  }
}

package datadog.trace.instrumentation.lettuce4;

import com.lambdaworks.redis.RedisURI;
import com.lambdaworks.redis.api.StatefulConnection;
import com.lambdaworks.redis.api.StatefulRedisConnection;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import net.bytebuddy.asm.Advice;

public class RedisConnectionAdvice {

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static AgentScope onEnter(@Advice.Argument(1) final RedisURI redisURI) {
    return InstrumentationPoints.beforeConnect(redisURI);
  }

  @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
  public static void onExit(
      @Advice.Argument(1) final RedisURI redisURI,
      @Advice.Enter final AgentScope scope,
      @Advice.Thrown final Throwable throwable,
      @Advice.Return final StatefulRedisConnection connection) {
    if (connection != null) {
      InstrumentationContext.get(StatefulConnection.class, RedisURI.class)
          .put(connection, redisURI);
    }
    InstrumentationPoints.afterConnect(scope, throwable);
  }
}

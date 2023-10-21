package datadog.trace.instrumentation.vertx_redis_client_4;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.instrumentation.vertx_redis_client_4.VertxRedisClientDecorator.DECORATE;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.vertx.redis.client.impl.RedisStandaloneConnection;
import net.bytebuddy.asm.Advice;

public class RedisConnectAdvice {
  @Advice.OnMethodEnter
  public static AgentScope before() {
    final AgentSpan span = activeSpan();
    DECORATE.logging("Connect span", span);
    if (span != null && VertxRedisClientDecorator.REDIS_COMMAND.equals(span.getOperationName())) {
      return activateSpan(span, true);
    }
    return null;
  }

  @Advice.OnMethodExit
  public static void after(@Advice.Enter final AgentScope scope) {
    if (scope != null) {
      scope.close();
    }
  }

  // Limit ourselves to 4.x by using for the RedisStandaloneConnection class that was added in 4.x
  private static void muzzleCheck(RedisStandaloneConnection connection) {
    connection.close(); // added in 4.x
  }
}

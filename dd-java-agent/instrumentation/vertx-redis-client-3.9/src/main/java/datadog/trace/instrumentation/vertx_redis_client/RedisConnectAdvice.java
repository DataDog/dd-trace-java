package datadog.trace.instrumentation.vertx_redis_client;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;

public class RedisConnectAdvice {
  @Advice.OnMethodEnter
  public static AgentScope before() {
    final AgentSpan span = activeSpan();
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
}

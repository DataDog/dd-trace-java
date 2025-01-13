package datadog.trace.instrumentation.vertx_redis_client;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isDefaultMethod;
import static net.bytebuddy.matcher.ElementMatchers.isVirtual;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.returns;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import java.util.HashMap;
import java.util.Map;

@AutoService(InstrumenterModule.class)
public class RedisAPIInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForKnownTypes, Instrumenter.HasMethodAdvice {
  public RedisAPIInstrumentation() {
    super("vertx", "vertx-redis-client");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".ResponseHandlerWrapper", packageName + ".VertxRedisClientDecorator",
    };
  }

  @Override
  public Map<String, String> contextStore() {
    Map<String, String> contextStores = new HashMap<>();
    contextStores.put("io.vertx.redis.client.RedisAPI", packageName + ".ResponseHandlerWrapper");
    contextStores.put("io.vertx.redis.client.RedisConnection", "io.vertx.core.net.SocketAddress");
    return contextStores;
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
      "io.vertx.redis.client.RedisAPI", "io.vertx.redis.client.impl.RedisAPIImpl"
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isVirtual().and(isDefaultMethod()).and(returns(named("io.vertx.redis.client.RedisAPI"))),
        packageName + ".RedisAPICallAdvice");
    transformer.applyAdvice(
        named("send").and(not(isDefaultMethod())).and(returns(named("io.vertx.core.Future"))),
        packageName + ".RedisAPIImplSendAdvice");
  }
}

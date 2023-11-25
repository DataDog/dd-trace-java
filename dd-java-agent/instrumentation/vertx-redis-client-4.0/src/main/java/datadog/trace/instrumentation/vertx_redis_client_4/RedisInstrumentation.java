package datadog.trace.instrumentation.vertx_redis_client_4;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isDeclaredBy;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import java.util.HashMap;
import java.util.Map;

@AutoService(Instrumenter.class)
public class RedisInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForKnownTypes {
  public RedisInstrumentation() {
    super("vertx", "vertx-redis-client");
  }

  @Override
  public Map<String, String> contextStore() {
    Map<String, String> contextStores = new HashMap<>();
    contextStores.put("io.vertx.redis.client.Command", UTF8BytesString.class.getName());
    contextStores.put("io.vertx.redis.client.Request", "datadog.trace.api.Pair");
    contextStores.put("io.vertx.redis.client.RedisConnection", "io.vertx.core.net.SocketAddress");
    return contextStores;
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".ResponseHandler", packageName + ".VertxRedisClientDecorator",
    };
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
      "io.vertx.redis.client.Redis",
      "io.vertx.redis.client.impl.BaseRedisClient",
      "io.vertx.redis.client.impl.RedisClient",
      "io.vertx.redis.client.impl.RedisClusterClient",
      "io.vertx.redis.client.impl.RedisSentinelClient",
      "io.vertx.redis.client.impl.RedisStandaloneConnection",
      "io.vertx.redis.client.RedisConnection",
      "io.vertx.redis.client.impl.RedisClusterConnection"
    };
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isPublic()
            .and(named("send"))
            .and(takesArguments(1))
            .and(takesArgument(0, named("io.vertx.redis.client.Request")))
            .and(returns(named("io.vertx.core.Future"))),
        packageName + ".RedisSendAdvice");

    transformation.applyAdvice(
        isDeclaredBy(named("io.vertx.redis.client.impl.RedisStandaloneConnection"))
            .and(isConstructor())
            .and(takesArgument(3, named("io.vertx.core.net.NetSocket"))),
        packageName + ".RedisConnectionConstructorAdvice");

    transformation.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(named("connect"))
            .and(takesArguments(0))
            .and(returns(named("io.vertx.core.Future"))),
        packageName + ".RedisConnectAdvice");
  }
}

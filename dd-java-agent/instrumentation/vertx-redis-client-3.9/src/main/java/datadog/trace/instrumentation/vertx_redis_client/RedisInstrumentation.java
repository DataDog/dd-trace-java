package datadog.trace.instrumentation.vertx_redis_client;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

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
    contextStores.put("io.vertx.redis.client.Request", Boolean.class.getName());
    return contextStores;
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".ResponseHandlerWrapper", packageName + ".VertxRedisClientDecorator",
    };
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
      "io.vertx.redis.client.Redis",
      "io.vertx.redis.client.impl.RedisConnectionImpl",
      "io.vertx.redis.client.impl.RedisClusterConnection"
    };
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(named("send"))
            .and(takesArgument(0, named("io.vertx.redis.client.Request")))
            .and(takesArgument(1, named("io.vertx.core.Handler"))),
        packageName + ".RedisSendAdvice");
  }
}

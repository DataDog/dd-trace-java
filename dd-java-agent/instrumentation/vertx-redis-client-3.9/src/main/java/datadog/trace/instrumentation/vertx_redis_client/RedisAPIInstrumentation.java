package datadog.trace.instrumentation.vertx_redis_client;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isDefaultMethod;
import static net.bytebuddy.matcher.ElementMatchers.isVirtual;
import static net.bytebuddy.matcher.ElementMatchers.returns;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import java.util.Map;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class RedisAPIInstrumentation extends Instrumenter.Tracing {
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
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return named("io.vertx.redis.client.RedisAPI");
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return singletonMap(
        isVirtual().and(isDefaultMethod()).and(returns(named("io.vertx.redis.client.RedisAPI"))),
        packageName + ".RedisAPICallAdvice");
  }
}

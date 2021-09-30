package datadog.trace.instrumentation.vertx_redis_client;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static net.bytebuddy.matcher.ElementMatchers.isDefaultMethod;
import static net.bytebuddy.matcher.ElementMatchers.isVirtual;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.returns;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;

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
  public Map<String, String> contextStore() {
    Map<String, String> contextStores = new HashMap<>();
    contextStores.put("io.vertx.redis.client.RedisAPI", packageName + ".ResponseHandlerWrapper");
    return contextStores;
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return namedOneOf("io.vertx.redis.client.RedisAPI", "io.vertx.redis.client.impl.RedisAPIImpl");
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isVirtual().and(isDefaultMethod()).and(returns(named("io.vertx.redis.client.RedisAPI"))),
        packageName + ".RedisAPICallAdvice");
    transformation.applyAdvice(
        named("send")
            .and(not(ElementMatchers.isDefaultMethod()))
            .and(returns(named("io.vertx.core.Future"))),
        packageName + ".RedisAPIImplSendAdvice");
  }
}

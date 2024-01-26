package datadog.trace.instrumentation.lettuce4;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import java.util.Collections;
import java.util.Map;

@AutoService(Instrumenter.class)
public final class LettuceClientInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType {

  public LettuceClientInstrumentation() {
    super("lettuce", "lettuce-4");
  }

  @Override
  public String instrumentedType() {
    return "com.lambdaworks.redis.RedisClient";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".LettuceClientDecorator", packageName + ".InstrumentationPoints"
    };
  }

  @Override
  public Map<String, String> contextStore() {
    return Collections.singletonMap(
        "com.lambdaworks.redis.api.StatefulConnection", "com.lambdaworks.redis.RedisURI");
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(named("connectStandalone")),
        // Cannot reference class directly here because it would lead to class load failure on Java7
        packageName + ".RedisConnectionAdvice");
  }
}

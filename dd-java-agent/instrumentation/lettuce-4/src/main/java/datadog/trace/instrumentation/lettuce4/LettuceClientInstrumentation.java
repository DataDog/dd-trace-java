package datadog.trace.instrumentation.lettuce4;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import java.util.Collections;
import java.util.Map;

@AutoService(InstrumenterModule.class)
public final class LettuceClientInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

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

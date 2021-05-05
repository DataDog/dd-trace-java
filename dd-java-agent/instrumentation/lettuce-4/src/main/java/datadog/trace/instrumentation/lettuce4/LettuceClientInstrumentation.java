package datadog.trace.instrumentation.lettuce4;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public final class LettuceClientInstrumentation extends Instrumenter.Tracing {

  public LettuceClientInstrumentation() {
    super("lettuce", "lettuce-4");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return named("com.lambdaworks.redis.RedisClient");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".LettuceClientDecorator", packageName + ".InstrumentationPoints"
    };
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod().and(named("connectStandalone")),
        // Cannot reference class directly here because it would lead to class load failure on Java7
        packageName + ".RedisConnectionAdvice");
  }
}

package datadog.trace.instrumentation.lettuce5;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.nameEndsWith;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.nameStartsWith;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPrivate;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public final class LettuceClientInstrumentation extends Instrumenter.Tracing {

  public LettuceClientInstrumentation() {
    super("lettuce", "lettuce-5");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return named("io.lettuce.core.RedisClient");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".LettuceClientDecorator",
      packageName + ".LettuceInstrumentationUtil",
      packageName + ".LettuceAsyncBiFunction"
    };
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod()
            .and(isPrivate())
            .and(returns(named("io.lettuce.core.ConnectionFuture")))
            .and(nameStartsWith("connect"))
            .and(nameEndsWith("Async"))
            .and(takesArgument(1, named("io.lettuce.core.RedisURI"))),
        // Cannot reference class directly here because it would lead to class load failure on Java7
        packageName + ".ConnectionFutureAdvice");
  }
}

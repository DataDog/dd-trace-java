package datadog.trace.instrumentation.lettuce5;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.nameEndsWith;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.nameStartsWith;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class LettuceReactiveCommandsInstrumentation extends Instrumenter.Tracing {

  public LettuceReactiveCommandsInstrumentation() {
    super("lettuce", "lettuce-5", "lettuce-5-rx");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return named("io.lettuce.core.AbstractRedisReactiveCommands");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".LettuceClientDecorator",
      packageName + ".LettuceInstrumentationUtil",
      packageName + ".rx.LettuceMonoCreationAdvice",
      packageName + ".rx.LettuceMonoDualConsumer",
      packageName + ".rx.LettuceFluxCreationAdvice",
      packageName + ".rx.LettuceFluxTerminationRunnable",
      packageName + ".rx.LettuceFluxTerminationRunnable$FluxOnSubscribeConsumer"
    };
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod()
            .and(named("createMono"))
            .and(takesArgument(0, named("java.util.function.Supplier")))
            .and(returns(named("reactor.core.publisher.Mono"))),
        packageName + ".rx.LettuceMonoCreationAdvice");
    transformation.applyAdvice(
        isMethod()
            .and(nameStartsWith("create"))
            .and(nameEndsWith("Flux"))
            .and(isPublic())
            .and(takesArgument(0, named("java.util.function.Supplier")))
            .and(returns(named("reactor.core.publisher.Flux"))),
        packageName + ".rx.LettuceFluxCreationAdvice");
  }
}

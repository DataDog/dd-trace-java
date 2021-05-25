package datadog.trace.instrumentation.java.concurrent;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.java.concurrent.Wrapper;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class CustomExecutorInstrumentation extends Instrumenter.Tracing {

  public CustomExecutorInstrumentation() {
    super("java_concurrent", "custom_executor");
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return namedOneOf(Config.get().getTraceExecutors());
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        named("execute").and(takesArgument(0, named("java.lang.Runnable"))).and(takesArguments(1)),
        getClass().getName() + "$Wrap");
  }

  public static final class Wrap {
    @Advice.OnMethodEnter
    public static void wrap(@Advice.Argument(value = 0, readOnly = false) Runnable task) {
      task = Wrapper.wrap(task);
    }
  }
}

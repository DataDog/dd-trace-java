package datadog.trace.instrumentation.main;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.any;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;
import static net.bytebuddy.matcher.ElementMatchers.any;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.Config;
import datadog.trace.api.WithGlobalTracer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.description.type.TypeDescription;

@AutoService(Instrumenter.class)
public class MainInstrumentation extends Instrumenter.Tracing {

  public MainInstrumentation() {
    super("main");
  }

  @Override
  protected boolean defaultEnabled() {
    return false;
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(isStatic())
            .and(named("main"))
            .and(takesArguments(1))
            .and(takesArgument(0, String[].class)),
        getClass().getName() + "$MainAdvice");
  }

  public static class MainAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void methodExit() {
      Class.forName("datadog.trace.bootstrap.Agent")
          .getMethod("shutdown", Boolean.TYPE)
          .invoke(null, true);
    }
  }
}

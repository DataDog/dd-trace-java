package datadog.trace.instrumentation.undertow;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

@AutoService(Instrumenter.class)
public final class UndertowInstrumentation extends Instrumenter.Tracing {

  public UndertowInstrumentation() {
    super("undertow", "wildfly");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return named("io.undertow.server.HttpServerExchange");
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod().and(named("dispatch")),
        //            .and(takesArgument(0, named("java.util.concurrent.Executor")))
        //            .and(takesArgument(1, named("java.lag.Runnable"))),
        getClass().getName() + "$DispatchAdvice");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      // add classes here
    };
  }

  public static class DispatchAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void enter() {
      System.err.println("Undertow dispatch");
    }
  }
}

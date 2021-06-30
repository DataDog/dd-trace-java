package datadog.trace.instrumentation.java.concurrent;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeScope;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.context.TraceScope;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class DisableAsyncPropagationForPeriodicScheduleInstrumentation
    extends Instrumenter.Tracing {
  public DisableAsyncPropagationForPeriodicScheduleInstrumentation() {
    super(AbstractExecutorInstrumentation.EXEC_NAME);
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return named("java.util.concurrent.ScheduledThreadPoolExecutor");
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        namedOneOf("scheduleAtFixedRate", "scheduleWithFixedDelay")
            .and(isMethod())
            .and(takesArgument(0, named(Runnable.class.getName()))),
        getClass().getName() + "$DisableAsyncPropagation");
  }

  public static final class DisableAsyncPropagation {
    @Advice.OnMethodEnter
    public static boolean before() {
      TraceScope scope = activeScope();
      if (null != scope) {
        boolean wasEnabled = scope.isAsyncPropagating();
        scope.setAsyncPropagation(false);
        return wasEnabled;
      }
      return false;
    }

    @Advice.OnMethodExit
    public static void after(@Advice.Enter boolean wasEnabled) {
      if (wasEnabled) {
        TraceScope scope = activeScope();
        if (null != scope) {
          scope.setAsyncPropagation(wasEnabled);
        }
      }
    }
  }
}

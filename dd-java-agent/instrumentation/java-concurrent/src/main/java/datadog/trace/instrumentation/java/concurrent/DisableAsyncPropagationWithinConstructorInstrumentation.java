package datadog.trace.instrumentation.java.concurrent;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeScope;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.context.TraceScope;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class DisableAsyncPropagationWithinConstructorInstrumentation extends Instrumenter.Tracing {
  public DisableAsyncPropagationWithinConstructorInstrumentation() {
    super(AbstractExecutorInstrumentation.EXEC_NAME);
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return namedOneOf("rx.schedulers.CachedThreadScheduler$CachedWorkerPool");
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(isConstructor(), getClass().getName() + "$DisableAsyncPropagation");
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

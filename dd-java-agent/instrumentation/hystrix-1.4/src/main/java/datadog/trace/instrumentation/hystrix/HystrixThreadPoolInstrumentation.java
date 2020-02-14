package datadog.trace.instrumentation.hystrix;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeScope;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.context.TraceScope;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class HystrixThreadPoolInstrumentation extends Instrumenter.Default {

  public HystrixThreadPoolInstrumentation() {
    super("hystrix");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return named(
        "com.netflix.hystrix.strategy.concurrency.HystrixContextScheduler$ThreadPoolWorker");
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return singletonMap(
        isMethod().and(named("schedule")).and(takesArguments(1)),
        HystrixThreadPoolInstrumentation.class.getName() + "$EnableAsyncAdvice");
  }

  public static class EnableAsyncAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static boolean enableAsyncTracking() {
      final TraceScope scope = activeScope();
      if (scope != null) {
        if (!scope.isAsyncPropagating()) {
          scope.setAsyncPropagation(true);
          return true;
        }
      }
      return false;
    }

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void disableAsyncTracking(@Advice.Enter final boolean wasEnabled) {
      if (wasEnabled) {
        final TraceScope scope = activeScope();
        if (scope != null) {
          scope.setAsyncPropagation(false);
        }
      }
    }
  }
}

package datadog.trace.instrumentation.caffeine;

import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import java.util.concurrent.ForkJoinPool;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public final class BoundedLocalCacheInstrumentation extends Instrumenter.Tracing {

  public BoundedLocalCacheInstrumentation() {
    super("caffeine");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return named("com.github.benmanes.caffeine.cache.BoundedLocalCache");
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        named("scheduleDrainBuffers").and(takesArguments(0)),
        getClass().getName() + "$ScheduleDrainBuffers");
  }

  public static class ScheduleDrainBuffers {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter() {
      CallDepthThreadLocalMap.incrementCallDepth(ForkJoinPool.class);
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void onExit() {
      CallDepthThreadLocalMap.decrementCallDepth(ForkJoinPool.class);
    }
  }
}

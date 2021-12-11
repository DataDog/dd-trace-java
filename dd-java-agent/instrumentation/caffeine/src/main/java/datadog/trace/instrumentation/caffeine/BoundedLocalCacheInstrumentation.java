package datadog.trace.instrumentation.caffeine;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.noopSpan;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
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

  public static final class ScheduleDrainBuffers {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope enter() {
      return activateSpan(noopSpan());
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exit(@Advice.Enter final AgentScope scope) {
      scope.close();
    }
  }
}

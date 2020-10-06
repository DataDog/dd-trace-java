package datadog.trace.instrumentation.java.concurrent;

import static datadog.trace.agent.tooling.bytebuddy.matcher.DDElementMatchers.extendsClass;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeScope;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.noopSpan;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.named;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.context.TraceScope;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;

/**
 * Sometimes classes do lazy initialization for scheduling of tasks. If this is done during a trace
 * it can cause the trace to never be reported. Add matchers below to disable async propagation
 * during this period.
 */
@Slf4j
@AutoService(Instrumenter.class)
public final class AsyncPropagatingDisableInstrumentation implements Instrumenter {

  @Override
  public AgentBuilder instrument(AgentBuilder agentBuilder) {
    return new DisableAsyncInstrumentation(
            extendsClass(named("rx.Scheduler$Worker")), named("schedulePeriodically"))
        .instrument(agentBuilder);
  }

  // Not Using AutoService to hook up this instrumentation
  public static class DisableAsyncInstrumentation extends Default {

    private final ElementMatcher<? super TypeDescription> typeMatcher;
    private final ElementMatcher<? super MethodDescription> methodMatcher;

    /** No-arg constructor only used by muzzle and tests. */
    public DisableAsyncInstrumentation() {
      this(ElementMatchers.<TypeDescription>none(), ElementMatchers.<MethodDescription>none());
    }

    public DisableAsyncInstrumentation(
        final ElementMatcher<? super TypeDescription> typeMatcher,
        final ElementMatcher<? super MethodDescription> methodMatcher) {
      super("java_concurrent");
      this.typeMatcher = typeMatcher;
      this.methodMatcher = methodMatcher;
    }

    @Override
    public ElementMatcher<? super TypeDescription> typeMatcher() {
      return typeMatcher;
    }

    @Override
    public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
      return singletonMap(
          methodMatcher,
          AsyncPropagatingDisableInstrumentation.class.getName() + "$DisableAsyncAdvice");
    }
  }

  public static class DisableAsyncAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope enter() {
      final TraceScope scope = activeScope();
      if (scope != null && scope.isAsyncPropagating()) {
        return activateSpan(noopSpan());
      }
      return null;
    }

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void exit(@Advice.Enter final AgentScope scope) {
      if (scope != null) {
        scope.close();
        // Don't need to finish noop span.
      }
    }
  }
}

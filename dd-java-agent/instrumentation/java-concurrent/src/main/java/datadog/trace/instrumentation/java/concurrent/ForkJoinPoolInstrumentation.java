package datadog.trace.instrumentation.java.concurrent;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeScope;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isFinal;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.named;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import datadog.trace.context.TraceScope;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ForkJoinTask;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class ForkJoinPoolInstrumentation extends Instrumenter.Default {

  public ForkJoinPoolInstrumentation() {
    super("java-concurrent", "fjp");
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return named("java.util.concurrent.ForkJoinPool");
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap("java.util.concurrent.ForkJoinTask", State.class.getName());
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return Collections.singletonMap(
        isMethod().and(named("externalPush")).and(isFinal()),
        getClass().getName() + "$ExternalPush");
  }

  public static final class ExternalPush {
    @Advice.OnMethodEnter
    public static <T> void externalPush(@Advice.Argument(0) ForkJoinTask<T> task) {
      if (!ExcludeFilter.exclude(ExcludeFilter.ExcludeType.EXECUTOR, task)) {
        TraceScope activeScope = activeScope();
        if (null != activeScope) {
          InstrumentationContext.get(ForkJoinTask.class, State.class)
            .putIfAbsent(task, State.FACTORY)
            .captureAndSetContinuation(activeScope);
        }
      }
    }
  }
}

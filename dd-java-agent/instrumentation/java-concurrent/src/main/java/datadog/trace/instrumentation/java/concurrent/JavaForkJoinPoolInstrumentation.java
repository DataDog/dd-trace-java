package datadog.trace.instrumentation.java.concurrent;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeScope;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils.cancelTask;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.FORK_JOIN_TASK;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.named;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.Platform;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import datadog.trace.context.TraceScope;
import java.util.Map;
import java.util.concurrent.ForkJoinTask;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class JavaForkJoinPoolInstrumentation extends Instrumenter.Tracing {

  public JavaForkJoinPoolInstrumentation() {
    super("java_concurrent", "fjp");
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
    if (Platform.isJavaVersionAtLeast(8)) {
      return singletonMap(
          isMethod().and(namedOneOf("externalPush", "externalSubmit")),
          getClass().getName() + "$ExternalPush");
    }
    return singletonMap(
        isMethod().and(namedOneOf("forkOrSubmit", "invoke")),
        getClass().getName() + "$ExternalPush");
  }

  public static final class ExternalPush {
    @Advice.OnMethodEnter
    public static <T> void externalPush(@Advice.Argument(0) ForkJoinTask<T> task) {
      TraceScope activeScope = activeScope();
      if (null != activeScope) {
        if (!ExcludeFilter.exclude(FORK_JOIN_TASK, task)) {
          InstrumentationContext.get(ForkJoinTask.class, State.class)
              .putIfAbsent(task, State.FACTORY)
              .captureAndSetContinuation(activeScope);
        }
      }
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static <T> void cleanup(
        @Advice.Argument(0) ForkJoinTask<T> task, @Advice.Thrown Throwable thrown) {
      if (null != thrown) {
        cancelTask(InstrumentationContext.get(ForkJoinTask.class, State.class), task);
      }
    }
  }
}

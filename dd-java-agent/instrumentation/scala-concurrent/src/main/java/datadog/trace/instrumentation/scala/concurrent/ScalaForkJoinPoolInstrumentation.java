package datadog.trace.instrumentation.scala.concurrent;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeScope;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils.cancelTask;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import datadog.trace.context.TraceScope;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import scala.concurrent.forkjoin.ForkJoinTask;

@Slf4j
@AutoService(Instrumenter.class)
public final class ScalaForkJoinPoolInstrumentation extends Instrumenter.Default {

  public ScalaForkJoinPoolInstrumentation() {
    super("java_concurrent", "scala_concurrent");
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return named("scala.concurrent.forkjoin.ForkJoinPool");
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap("scala.concurrent.forkjoin.ForkJoinTask", State.class.getName());
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return singletonMap(
        isMethod()
            .and(namedOneOf("doSubmit", "externalPush"))
            .and(takesArgument(0, named("scala.concurrent.forkjoin.ForkJoinTask"))),
        getClass().getName() + "$StartTask");
  }

  public static final class StartTask {
    @Advice.OnMethodEnter
    public static <T> void before(@Advice.Argument(0) ForkJoinTask<T> task) {
      TraceScope activeScope = activeScope();
      if (null != activeScope) {
        InstrumentationContext.get(ForkJoinTask.class, State.class)
            .putIfAbsent(task, State.FACTORY)
            .captureAndSetContinuation(activeScope);
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

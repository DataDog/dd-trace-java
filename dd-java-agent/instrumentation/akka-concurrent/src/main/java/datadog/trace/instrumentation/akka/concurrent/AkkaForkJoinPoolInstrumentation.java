package datadog.trace.instrumentation.akka.concurrent;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeScope;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils.cancelTask;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import akka.dispatch.forkjoin.ForkJoinTask;
import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import datadog.trace.context.TraceScope;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public final class AkkaForkJoinPoolInstrumentation extends Instrumenter.Tracing {

  public AkkaForkJoinPoolInstrumentation() {
    super("java_concurrent", "akka_concurrent");
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return named("akka.dispatch.forkjoin.ForkJoinPool");
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap("akka.dispatch.forkjoin.ForkJoinTask", State.class.getName());
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod().and(namedOneOf("externalPush", "fullExternalPush")),
        getClass().getName() + "$ExternalPush");
  }

  public static final class ExternalPush {
    @Advice.OnMethodEnter
    public static <T> void externalPush(@Advice.Argument(0) ForkJoinTask<T> task) {
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

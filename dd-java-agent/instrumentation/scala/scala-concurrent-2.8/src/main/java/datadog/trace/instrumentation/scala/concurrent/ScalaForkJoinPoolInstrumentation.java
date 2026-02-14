package datadog.trace.instrumentation.scala.concurrent;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils.cancelTask;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils.capture;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.FORK_JOIN_TASK;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.exclude;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import net.bytebuddy.asm.Advice;
import scala.concurrent.forkjoin.ForkJoinTask;

public final class ScalaForkJoinPoolInstrumentation
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  @Override
  public String instrumentedType() {
    return "scala.concurrent.forkjoin.ForkJoinPool";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(namedOneOf("doSubmit", "externalPush"))
            .and(takesArgument(0, named("scala.concurrent.forkjoin.ForkJoinTask"))),
        getClass().getName() + "$StartTask");
  }

  public static final class StartTask {
    @Advice.OnMethodEnter
    public static <T> void before(@Advice.Argument(0) ForkJoinTask<T> task) {
      if (!exclude(FORK_JOIN_TASK, task)) {
        capture(InstrumentationContext.get(ForkJoinTask.class, State.class), task);
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

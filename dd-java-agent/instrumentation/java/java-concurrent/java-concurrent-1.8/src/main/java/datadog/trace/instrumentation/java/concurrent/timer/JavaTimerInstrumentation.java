package datadog.trace.instrumentation.java.concurrent.timer;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils.cancelTask;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils.capture;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.RUNNABLE;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.exclude;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPrivate;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import java.util.TimerTask;
import net.bytebuddy.asm.Advice;

public class JavaTimerInstrumentation
    implements Instrumenter.ForBootstrap, Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  @Override
  public String instrumentedType() {
    return "java.util.Timer";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(isPrivate())
            .and(
                named("sched")
                    .and(takesArguments(3))
                    .and(takesArgument(0, named("java.util.TimerTask")))
                    .and(takesArgument(1, long.class))
                    .and(takesArgument(2, long.class))),
        getClass().getName() + "$TimerScheduleAdvice");
  }

  public static final class TimerScheduleAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void before(@Advice.Argument(0) TimerTask task, @Advice.Argument(2) long period) {
      // don't propagate fixed time / rate executions
      if (period != 0) {
        return;
      }
      if (!exclude(RUNNABLE, task)) {
        ContextStore<Runnable, State> contextStore =
            InstrumentationContext.get(Runnable.class, State.class);
        capture(contextStore, task);
      }
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void after(@Advice.Argument(0) TimerTask task, @Advice.Thrown Throwable thrown) {
      if (null != thrown && !exclude(RUNNABLE, task)) {
        cancelTask(InstrumentationContext.get(Runnable.class, State.class), task);
      }
    }
  }
}

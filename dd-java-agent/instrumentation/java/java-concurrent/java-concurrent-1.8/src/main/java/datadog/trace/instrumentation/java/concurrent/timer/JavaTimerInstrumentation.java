package datadog.trace.instrumentation.java.concurrent.timer;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils.cancelTask;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils.capture;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.RUNNABLE;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.exclude;
import static datadog.trace.instrumentation.java.concurrent.ConcurrentInstrumentationNames.EXECUTOR_INSTRUMENTATION_NAME;
import static datadog.trace.instrumentation.java.concurrent.ConcurrentInstrumentationNames.RUNNABLE_INSTRUMENTATION_NAME;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPrivate;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import java.util.Map;
import java.util.TimerTask;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public class JavaTimerInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForBootstrap, Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  public JavaTimerInstrumentation() {
    super("java_timer", EXECUTOR_INSTRUMENTATION_NAME, RUNNABLE_INSTRUMENTATION_NAME);
  }

  @Override
  public String instrumentedType() {
    return "java.util.Timer";
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap("java.lang.Runnable", State.class.getName());
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

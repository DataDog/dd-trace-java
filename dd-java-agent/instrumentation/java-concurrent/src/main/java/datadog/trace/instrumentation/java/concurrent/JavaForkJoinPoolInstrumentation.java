package datadog.trace.instrumentation.java.concurrent;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils.cancelTask;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils.capture;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.FORK_JOIN_TASK;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.exclude;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.java.concurrent.QueueTimerHelper;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import java.util.Map;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import net.bytebuddy.asm.Advice;

@AutoService(Instrumenter.class)
public class JavaForkJoinPoolInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForBootstrap, Instrumenter.ForSingleType {

  public JavaForkJoinPoolInstrumentation() {
    super("java_concurrent", "fjp");
  }

  @Override
  public String instrumentedType() {
    return "java.util.concurrent.ForkJoinPool";
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap("java.util.concurrent.ForkJoinTask", State.class.getName());
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    String name = getClass().getName();
    transformation.applyAdvice(
        isMethod().and(namedOneOf("externalPush", "externalSubmit")), name + "$ExternalPush");
    // Java 21 has a new method name and changed signature
    transformation.applyAdvice(isMethod().and(named("poolSubmit")), name + "$PoolSubmit");
  }

  public static final class ExternalPush {
    @SuppressWarnings("rawtypes")
    @Advice.OnMethodEnter
    public static <T> void externalPush(
        @Advice.This ForkJoinPool pool, @Advice.Argument(0) ForkJoinTask<T> task) {
      if (!exclude(FORK_JOIN_TASK, task)) {
        ContextStore<ForkJoinTask, State> contextStore =
            InstrumentationContext.get(ForkJoinTask.class, State.class);
        capture(contextStore, task, true);
        QueueTimerHelper.startQueuingTimer(contextStore, pool.getClass(), task);
      }
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static <T> void cleanup(
        @Advice.Argument(0) ForkJoinTask<T> task, @Advice.Thrown Throwable thrown) {
      if (null != thrown && !exclude(FORK_JOIN_TASK, task)) {
        cancelTask(InstrumentationContext.get(ForkJoinTask.class, State.class), task);
      }
    }
  }

  public static final class PoolSubmit {
    @Advice.OnMethodEnter
    public static <T> void poolSubmit(
        @Advice.This ForkJoinPool pool, @Advice.Argument(1) ForkJoinTask<T> task) {
      if (!exclude(FORK_JOIN_TASK, task)) {
        ContextStore<ForkJoinTask, State> contextStore =
            InstrumentationContext.get(ForkJoinTask.class, State.class);
        capture(contextStore, task, true);
        QueueTimerHelper.startQueuingTimer(contextStore, pool.getClass(), task);
      }
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static <T> void cleanup(
        @Advice.Argument(1) ForkJoinTask<T> task, @Advice.Thrown Throwable thrown) {
      if (null != thrown && !exclude(FORK_JOIN_TASK, task)) {
        cancelTask(InstrumentationContext.get(ForkJoinTask.class, State.class), task);
      }
    }
  }
}

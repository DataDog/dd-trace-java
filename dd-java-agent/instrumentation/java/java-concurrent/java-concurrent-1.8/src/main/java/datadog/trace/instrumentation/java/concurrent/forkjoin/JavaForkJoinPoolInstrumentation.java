package datadog.trace.instrumentation.java.concurrent.forkjoin;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils.cancelTask;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils.capture;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.FORK_JOIN_TASK;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.exclude;
import static datadog.trace.instrumentation.java.concurrent.ConcurrentInstrumentationNames.EXECUTOR_INSTRUMENTATION_NAME;
import static datadog.trace.instrumentation.java.concurrent.ConcurrentInstrumentationNames.FORK_JOIN_POOL_INSTRUMENTATION_NAME;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import java.util.Map;
import java.util.concurrent.ForkJoinTask;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public class JavaForkJoinPoolInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForBootstrap, Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public JavaForkJoinPoolInstrumentation() {
    super(EXECUTOR_INSTRUMENTATION_NAME, FORK_JOIN_POOL_INSTRUMENTATION_NAME);
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
  public void methodAdvice(MethodTransformer transformer) {
    String name = getClass().getName();
    transformer.applyAdvice(
        isMethod().and(namedOneOf("externalPush", "externalSubmit")), name + "$ExternalPush");
    // Java 21 has a new method name and changed signature
    transformer.applyAdvice(isMethod().and(named("poolSubmit")), name + "$PoolSubmit");
  }

  public static final class ExternalPush {
    @SuppressWarnings("rawtypes")
    @Advice.OnMethodEnter
    public static <T> void externalPush(@Advice.Argument(0) ForkJoinTask<T> task) {
      if (!exclude(FORK_JOIN_TASK, task)) {
        ContextStore<ForkJoinTask, State> contextStore =
            InstrumentationContext.get(ForkJoinTask.class, State.class);
        capture(contextStore, task);
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
    public static <T> void poolSubmit(@Advice.Argument(1) ForkJoinTask<T> task) {
      if (!exclude(FORK_JOIN_TASK, task)) {
        ContextStore<ForkJoinTask, State> contextStore =
            InstrumentationContext.get(ForkJoinTask.class, State.class);
        capture(contextStore, task);
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

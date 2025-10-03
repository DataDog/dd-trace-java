package datadog.trace.instrumentation.java.concurrent.forkjoin;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.declaresField;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.FORK_JOIN_TASK;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.exclude;
import static datadog.trace.instrumentation.java.concurrent.ConcurrentInstrumentationNames.EXECUTOR_INSTRUMENTATION_NAME;
import static datadog.trace.instrumentation.java.concurrent.ConcurrentInstrumentationNames.FORK_JOIN_POOL_INSTRUMENTATION_NAME;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.fieldType;
import static net.bytebuddy.matcher.ElementMatchers.isDeclaredBy;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.config.ProfilingConfig;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import datadog.trace.bootstrap.instrumentation.java.concurrent.QueueTimerHelper;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import java.util.Map;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public class JavaForkJoinWorkQueueInstrumentation extends InstrumenterModule.Profiling
    implements Instrumenter.ForBootstrap, Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public JavaForkJoinWorkQueueInstrumentation() {
    super(
        EXECUTOR_INSTRUMENTATION_NAME,
        FORK_JOIN_POOL_INSTRUMENTATION_NAME,
        FORK_JOIN_POOL_INSTRUMENTATION_NAME + "-workqueue");
  }

  @Override
  public String instrumentedType() {
    return "java.util.concurrent.ForkJoinPool$WorkQueue";
  }

  @Override
  public boolean isEnabled() {
    return super.isEnabled()
        && ConfigProvider.getInstance()
            .getBoolean(
                ProfilingConfig.PROFILING_QUEUEING_TIME_ENABLED,
                ProfilingConfig.PROFILING_QUEUEING_TIME_ENABLED_DEFAULT);
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap("java.util.concurrent.ForkJoinTask", State.class.getName());
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    String name = getClass().getName();
    transformer.applyAdvice(
        isMethod()
            .and(named("push"))
            .and(takesArgument(0, named("java.util.concurrent.ForkJoinTask")))
            .and(
                isDeclaredBy(
                    declaresField(fieldType(int.class).and(named("top")))
                        .and(declaresField(fieldType(int.class).and(named("base")))))),
        name + "$PushTask");
  }

  public static final class PushTask {
    @SuppressWarnings("rawtypes")
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static <T> void push(
        @Advice.This Object workQueue,
        @Advice.FieldValue("top") int top,
        @Advice.FieldValue("base") int base,
        @Advice.Argument(0) ForkJoinTask<T> task) {
      if (!exclude(FORK_JOIN_TASK, task)) {
        ContextStore<ForkJoinTask, State> contextStore =
            InstrumentationContext.get(ForkJoinTask.class, State.class);
        QueueTimerHelper.startQueuingTimer(
            contextStore, ForkJoinPool.class, workQueue.getClass(), top - base, task);
      }
    }
  }
}

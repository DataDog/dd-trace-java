package datadog.trace.instrumentation.netty40.concurrent;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.declaresField;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.nameEndsWith;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isDeclaredBy;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.config.ProfilingConfig;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import datadog.trace.bootstrap.instrumentation.java.concurrent.QueueTimerHelper;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.EventExecutorGroup;
import java.util.Collections;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.RunnableFuture;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public class SingleThreadEventExecutorInstrumentation extends InstrumenterModule.Profiling
    implements Instrumenter.ForKnownTypes, Instrumenter.HasMethodAdvice {
  public SingleThreadEventExecutorInstrumentation() {
    super("netty-concurrent", "netty-event-executor");
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
    return Collections.singletonMap(RunnableFuture.class.getName(), State.class.getName());
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
      "io.netty.util.concurrent.SingleThreadEventExecutor",
      "io.grpc.shaded.io.netty.util.concurrent.SingleThreadEventExecutor",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(named("addTask"))
            .and(takesArguments(1))
            .and(takesArgument(0, Runnable.class))
            .and(isDeclaredBy(declaresField(named("taskQueue")))),
        getClass().getName() + "$StartTimingTaskQueue");
    transformer.applyAdvice(
        isMethod()
            .and(named("schedule"))
            .and(takesArguments(1))
            .and(takesArgument(0, nameEndsWith("netty.util.concurrent.ScheduledFutureTask")))
            .and(isDeclaredBy(declaresField(named("delayedTaskQueue")))),
        getClass().getName() + "$StartTimingDelayedTaskQueue");
    transformer.applyAdvice(
        isMethod()
            .and(named("schedule"))
            .and(takesArguments(1))
            .and(takesArgument(0, nameEndsWith("netty.util.concurrent.ScheduledFutureTask")))
            .and(isDeclaredBy(declaresField(named("scheduledTaskQueue")))),
        getClass().getName() + "$StartTimingScheduledTaskQueue");
  }

  @SuppressWarnings("rawtypes")
  private static final class StartTimingTaskQueue {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void before(
        @Advice.FieldValue("taskQueue") Queue<?> taskQueue,
        @Advice.This EventExecutor executor,
        @Advice.Argument(0) Runnable task) {
      // should be a PromiseTask which is a RunnableFuture
      if (task instanceof RunnableFuture) {
        // detect double timing - also not interested in queueing time unless the task is traced
        // state == null => not traced, state.isTimed() => double timing
        ContextStore<RunnableFuture, State> contextStore =
            InstrumentationContext.get(RunnableFuture.class, State.class);
        State state = contextStore.get((RunnableFuture) task);
        if (state == null || state.isTimed() || taskQueue == null) {
          return;
        }
        Class<?> queueType = taskQueue == null ? null : taskQueue.getClass();
        int length = taskQueue == null ? 0 : taskQueue.size();
        EventExecutorGroup parent = executor.parent();
        Class<?> schedulerClass = parent == null ? executor.getClass() : parent.getClass();
        QueueTimerHelper.startQueuingTimer(state, schedulerClass, queueType, length, task);
      }
    }
  }

  @SuppressWarnings("rawtypes")
  private static final class StartTimingDelayedTaskQueue {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void before(
        @Advice.FieldValue("delayedTaskQueue") Queue<?> delayedTaskQueue,
        @Advice.This EventExecutor executor,
        @Advice.Argument(0) Runnable task) {
      // should be a PromiseTask which is a RunnableFuture
      if (task instanceof RunnableFuture) {
        // detect double timing - also not interested in queueing time unless the task is traced
        // state == null => not traced, state.isTimed() => double timing
        ContextStore<RunnableFuture, State> contextStore =
            InstrumentationContext.get(RunnableFuture.class, State.class);
        State state = contextStore.get((RunnableFuture) task);
        if (state == null || state.isTimed()) {
          return;
        }
        Class<?> queueType = delayedTaskQueue == null ? null : delayedTaskQueue.getClass();
        int length = delayedTaskQueue == null ? 0 : delayedTaskQueue.size();
        EventExecutorGroup parent = executor.parent();
        Class<?> schedulerClass = parent == null ? executor.getClass() : parent.getClass();
        QueueTimerHelper.startQueuingTimer(state, schedulerClass, queueType, length, task);
      }
    }
  }

  @SuppressWarnings("rawtypes")
  private static final class StartTimingScheduledTaskQueue {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void before(
        @Advice.FieldValue("scheduledTaskQueue") Queue<?> scheduledTaskQueue,
        @Advice.This EventExecutor executor,
        @Advice.Argument(0) Runnable task) {
      // should be a PromiseTask which is a RunnableFuture
      if (task instanceof RunnableFuture) {
        // detect double timing - also not interested in queueing time unless the task is traced
        // state == null => not traced, state.isTimed() => double timing
        ContextStore<RunnableFuture, State> contextStore =
            InstrumentationContext.get(RunnableFuture.class, State.class);
        State state = contextStore.get((RunnableFuture) task);
        if (state == null || state.isTimed()) {
          return;
        }
        Class<?> queueType = scheduledTaskQueue == null ? null : scheduledTaskQueue.getClass();
        int length = scheduledTaskQueue == null ? 0 : scheduledTaskQueue.size();
        EventExecutorGroup parent = executor.parent();
        Class<?> schedulerClass = parent == null ? executor.getClass() : parent.getClass();
        QueueTimerHelper.startQueuingTimer(state, schedulerClass, queueType, length, task);
      }
    }
  }
}

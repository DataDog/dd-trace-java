package datadog.trace.instrumentation.netty40.concurrent;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.nameEndsWith;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
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
import java.util.concurrent.RunnableFuture;
import net.bytebuddy.asm.Advice;

@AutoService(Instrumenter.class)
public class SingleThreadEventExecutorInstrumentation extends Instrumenter.Profiling
    implements Instrumenter.ForKnownTypes {
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
      "io.netty.util.concurrent.AbstractScheduledEventExecutor",
      "io.grpc.shaded.io.netty.util.concurrent.SingleThreadEventExecutor",
      "io.grpc.shaded.io.netty.util.concurrent.AbstractScheduledEventExecutor"
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(named("addTask"))
            .and(takesArguments(1))
            .and(takesArgument(0, Runnable.class)),
        getClass().getName() + "$StartTiming");
    // schedule may call execute so using the same instrumentation relies on detecting double
    // timing - earliest (schedule) must take precedence
    transformer.applyAdvice(
        isMethod()
            .and(named("schedule"))
            .and(takesArguments(1))
            .and(takesArgument(0, nameEndsWith("netty.util.concurrent.ScheduledFutureTask"))),
        getClass().getName() + "$StartTiming");
  }

  @SuppressWarnings("rawtypes")
  private static final class StartTiming {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void before(
        @Advice.This EventExecutor executor, @Advice.Argument(0) Runnable task) {
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
        EventExecutorGroup parent = executor.parent();
        Class<?> schedulerClass = parent == null ? executor.getClass() : parent.getClass();
        QueueTimerHelper.startQueuingTimer(state, schedulerClass, task);
      }
    }
  }
}

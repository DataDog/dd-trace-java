package datadog.trace.instrumentation.java.concurrent;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeScope;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.java.concurrent.CallableWrapper;
import datadog.trace.bootstrap.instrumentation.java.concurrent.ExecutorInstrumentationUtils;
import datadog.trace.bootstrap.instrumentation.java.concurrent.RunnableWrapper;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import datadog.trace.context.TraceScope;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class ScheduledExecutorServiceInstrumentation extends Instrumenter.Default {

  public ScheduledExecutorServiceInstrumentation() {
    super("java.util.concurrent.ScheduledThreadPoolExecutor");
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    // targeting the concrete type because it's an implementation detail
    // that this executor's submit calls schedule, and our instrumentation
    // is not idempotent
    return named("java.util.concurrent.ScheduledThreadPoolExecutor");
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    final Map<ElementMatcher<? super MethodDescription>, String> transformers = new HashMap<>();
    transformers.put(
        named("schedule").and(takesArgument(0, Runnable.class)),
        ScheduledExecutorServiceInstrumentation.class.getName() + "$ScheduleRunnableAdvice");
    transformers.put(
        named("schedule").and(takesArgument(0, Callable.class)),
        ScheduledExecutorServiceInstrumentation.class.getName() + "$ScheduleCallableAdvice");
    return transformers;
  }

  @Override
  public Map<String, String> contextStore() {
    final Map<String, String> map = new HashMap<>(4);
    map.put(Runnable.class.getName(), State.class.getName());
    map.put(Callable.class.getName(), State.class.getName());
    map.put(Future.class.getName(), State.class.getName());
    return Collections.unmodifiableMap(map);
  }

  public static class ScheduleRunnableAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static State enterSchedule(
        @Advice.This final Executor executor,
        @Advice.Argument(value = 0, readOnly = false) Runnable task) {
      final TraceScope scope = activeScope();
      task = new RunnableWrapper(task);
      final ContextStore<Runnable, State> contextStore =
          InstrumentationContext.get(Runnable.class, State.class);
      return ExecutorInstrumentationUtils.setupState(contextStore, task, scope);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exitSchedule(
        @Advice.This final Executor executor,
        @Advice.Enter final State state,
        @Advice.Thrown final Throwable throwable,
        @Advice.Return final Future future) {
      if (state != null && future != null) {
        final ContextStore<Future, State> contextStore =
            InstrumentationContext.get(Future.class, State.class);
        contextStore.put(future, state);
      }
      ExecutorInstrumentationUtils.cleanUpOnMethodExit(executor, state, throwable);
    }
  }

  public static class ScheduleCallableAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static State enterSchedule(
        @Advice.This final Executor executor,
        @Advice.Argument(value = 0, readOnly = false) Callable task) {
      final TraceScope scope = activeScope();
      task = new CallableWrapper(task);
      final ContextStore<Callable, State> contextStore =
          InstrumentationContext.get(Callable.class, State.class);
      return ExecutorInstrumentationUtils.setupState(contextStore, task, scope);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exitSchedule(
        @Advice.This final Executor executor,
        @Advice.Enter final State state,
        @Advice.Thrown final Throwable throwable,
        @Advice.Return final Future future) {
      if (state != null && future != null) {
        final ContextStore<Future, State> contextStore =
            InstrumentationContext.get(Future.class, State.class);
        contextStore.put(future, state);
      }
      ExecutorInstrumentationUtils.cleanUpOnMethodExit(executor, state, throwable);
    }
  }
}

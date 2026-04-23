package datadog.trace.bootstrap.instrumentation.java.concurrent;

import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.RUNNABLE;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.exclude;

import datadog.trace.api.GenericClassValue;
import datadog.trace.api.InstrumenterConfig;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.ProfilerContext;
import java.util.Set;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * This class is a helper for the ThreadPoolExecutorInstrumentation. The instrumentation has two
 * modes, where the legacy mode uses wrapping of the Runnable and the new mode uses the State
 * context store field in the actual Runnable. The ThreadLocal below is needed to transport the
 * AgentScope between two methods when using the context store approach. More details can be found
 * in the ThreadPoolExecutorInstrumentation.
 */
public final class TPEHelper {
  // If legacy is enabled, we will try to propagate via wrapping, if not we will try to propagate
  // via storing the state in the existing field in the Runnable
  private static final boolean useWrapping;
  // A ThreadPoolExecutor with one of these types will newer be propagated/wrapped
  private static final Set<String> excludedClasses;
  // A ThreadLocal to store the Scope between beforeExecute and afterExecute if wrapping is not used
  private static final ThreadLocal<AgentScope> threadLocalScope;
  // Stores System.nanoTime() at task activation so onTaskDeactivation can compute duration
  private static final ThreadLocal<Long> threadLocalActivationNano;
  // Stores the ProfilerContext captured at task activation so onTaskDeactivation can always be
  // called even when scope.span() returns null at afterExecute time (e.g. because a nested
  // ForkJoinTask instrumentation closed the outer scope before afterExecute fires).
  private static final ThreadLocal<ProfilerContext> threadLocalProfilerContext;

  private static final ClassValue<Boolean> WRAP =
      GenericClassValue.of(
          input -> {
            String className = input.getName();
            // We should always wrap anonymous lambda classes since we can't inject fields into
            // them, and they can never be anything more than a _pure_ Runnable. They have '/' in
            // their class name which is not allowed in 'normal' classes.
            return className.indexOf('/', className.lastIndexOf('.')) > 0;
          });

  static {
    InstrumenterConfig config = InstrumenterConfig.get();
    useWrapping = config.isLegacyInstrumentationEnabled(false, "trace.thread-pool-executors");
    excludedClasses = config.getTraceThreadPoolExecutorsExclude();
    if (useWrapping) {
      threadLocalScope = null;
      threadLocalActivationNano = null;
      threadLocalProfilerContext = null;
    } else {
      threadLocalScope = new ThreadLocal<>();
      threadLocalActivationNano = new ThreadLocal<>();
      threadLocalProfilerContext = new ThreadLocal<>();
    }
  }

  public static boolean useWrapping(Runnable task) {
    return useWrapping || task instanceof Wrapper || (task != null && WRAP.get(task.getClass()));
  }

  public static void setPropagate(
      ContextStore<ThreadPoolExecutor, Boolean> contextStore, ThreadPoolExecutor executor) {
    if (executor == null || contextStore == null || contextStore.get(executor) != null) {
      return;
    }
    String executorType = executor.getClass().getName();
    if (excludedClasses.contains(executorType)) {
      contextStore.put(executor, Boolean.FALSE);
    } else {
      contextStore.put(executor, Boolean.TRUE);
    }
  }

  public static boolean shouldPropagate(
      ContextStore<ThreadPoolExecutor, Boolean> contextStore, ThreadPoolExecutor executor) {
    if (executor == null || contextStore == null) {
      return false;
    }
    return Boolean.TRUE.equals(contextStore.get(executor));
  }

  public static void capture(ContextStore<Runnable, State> contextStore, Runnable task) {
    if (task != null && !exclude(RUNNABLE, task)) {
      AdviceUtils.capture(contextStore, task);
    }
  }

  public static AgentScope startScope(ContextStore<Runnable, State> contextStore, Runnable task) {
    if (task == null || exclude(RUNNABLE, task)) {
      return null;
    }
    AgentScope scope = AdviceUtils.startTaskScope(contextStore, task);
    if (scope != null && threadLocalActivationNano != null) {
      long startNano = System.nanoTime();
      threadLocalActivationNano.set(startNano);
      AgentSpan span = scope.span();
      if (span != null && span.context() instanceof ProfilerContext) {
        ProfilerContext profilerContext = (ProfilerContext) span.context();
        // Store the context so endScope can still call onTaskDeactivation if scope.span()
        // returns null at afterExecute time (e.g. a nested ForkJoinTask instrumentation closes
        // the outer scope before afterExecute fires for CompletableFuture$AsyncRun tasks).
        if (threadLocalProfilerContext != null) {
          threadLocalProfilerContext.set(profilerContext);
        }
        AgentTracer.get().getProfilingContext().onTaskActivation(profilerContext, startNano);
      }
    }
    return scope;
  }

  public static void setThreadLocalScope(AgentScope scope, Runnable task) {
    if (scope == null || task == null || exclude(RUNNABLE, task)) {
      return;
    }
    AgentScope current = threadLocalScope.get();
    if (current != null) {
      current.close();
    }
    threadLocalScope.set(scope);
  }

  public static AgentScope getAndClearThreadLocalScope(Runnable task) {
    if (task == null || exclude(RUNNABLE, task)) {
      return null;
    }
    AgentScope scope = threadLocalScope.get();
    // Intentionally use `.set(null)` instead of `.remove()` for performance reasons.
    // For details see: https://github.com/DataDog/dd-trace-java/pull/9856#discussion_r2527729963
    // noinspection ThreadLocalSetWithNull
    threadLocalScope.set(null);
    return scope;
  }

  public static void endScope(AgentScope scope, Runnable task) {
    if (task == null || exclude(RUNNABLE, task)) {
      return;
    }
    try {
      if (scope != null && threadLocalActivationNano != null) {
        Long startNano = threadLocalActivationNano.get();
        // noinspection ThreadLocalSetWithNull
        threadLocalActivationNano.set(null);
        // Retrieve and clear the stored profiler context.
        ProfilerContext storedCtx =
            threadLocalProfilerContext != null ? threadLocalProfilerContext.get() : null;
        // noinspection ThreadLocalSetWithNull
        if (threadLocalProfilerContext != null) {
          threadLocalProfilerContext.set(null);
        }
        if (startNano != null) {
          // Prefer the live span's context, fall back to the one stored at startScope() time.
          // The fallback handles the case where a nested ForkJoinTask instrumentation (e.g. for
          // CompletableFuture$AsyncRun) closes the outer scope before afterExecute fires,
          // causing scope.span() to return null and silently skipping onTaskDeactivation.
          AgentSpan span = scope.span();
          ProfilerContext ctx =
              (span != null && span.context() instanceof ProfilerContext)
                  ? (ProfilerContext) span.context()
                  : storedCtx;
          if (ctx != null) {
            AgentTracer.get().getProfilingContext().onTaskDeactivation(ctx, startNano);
          }
        }
      }
    } finally {
      AdviceUtils.endTaskScope(scope);
    }
  }

  public static void cancelTask(ContextStore<Runnable, State> contextStore, Runnable task) {
    if (task == null || exclude(RUNNABLE, task)) {
      return;
    }
    AdviceUtils.cancelTask(contextStore, task);
  }

  /**
   * Called at the start of a virtual-thread task (from {@code TaskRunnerInstrumentation}) after the
   * scope has been activated. Stores the activation timestamp and notifies the profiling
   * integration. Unlike {@link #startScope}, this method takes no task parameter and performs no
   * exclude-filter check, because the task ({@code ThreadPerTaskExecutor$TaskRunner}) is an
   * internal JDK class that must never be excluded.
   */
  public static void onVirtualThreadTaskStart(AgentScope scope) {
    if (scope == null || threadLocalActivationNano == null) {
      return;
    }
    long startNano = System.nanoTime();
    threadLocalActivationNano.set(startNano);
    AgentSpan span = scope.span();
    if (span != null && span.context() instanceof ProfilerContext) {
      AgentTracer.get()
          .getProfilingContext()
          .onTaskActivation((ProfilerContext) span.context(), startNano);
    }
  }

  /**
   * Called at the end of a virtual-thread task (from {@code TaskRunnerInstrumentation}) before the
   * scope is closed. Reads the activation timestamp stored by {@link #onVirtualThreadTaskStart} and
   * notifies the profiling integration so a synthetic {@code SpanNode} JFR event covering the
   * task's execution is emitted.
   */
  public static void onVirtualThreadTaskEnd(AgentScope scope) {
    if (scope == null || threadLocalActivationNano == null) {
      return;
    }
    Long startNano = threadLocalActivationNano.get();
    // noinspection ThreadLocalSetWithNull
    threadLocalActivationNano.set(null);
    if (startNano == null) {
      return;
    }
    AgentSpan span = scope.span();
    if (span != null && span.context() instanceof ProfilerContext) {
      AgentTracer.get()
          .getProfilingContext()
          .onTaskDeactivation((ProfilerContext) span.context(), startNano);
    }
  }
}

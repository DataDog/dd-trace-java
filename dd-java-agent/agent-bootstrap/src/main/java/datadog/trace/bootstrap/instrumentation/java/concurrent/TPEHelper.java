package datadog.trace.bootstrap.instrumentation.java.concurrent;

import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.RUNNABLE;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.exclude;

import datadog.trace.api.GenericClassValue;
import datadog.trace.api.InstrumenterConfig;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
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
    } else {
      threadLocalScope = new ThreadLocal<>();
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
    return AdviceUtils.startTaskScope(contextStore, task);
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
    AdviceUtils.endTaskScope(scope);
  }

  public static void cancelTask(ContextStore<Runnable, State> contextStore, Runnable task) {
    if (task == null || exclude(RUNNABLE, task)) {
      return;
    }
    AdviceUtils.cancelTask(contextStore, task);
  }
}

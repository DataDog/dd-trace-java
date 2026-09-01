package datadog.trace.bootstrap.instrumentation.java.concurrent;

import static datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils.shouldCapture;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.RUNNABLE;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.exclude;

import datadog.context.Context;
import datadog.context.ContextContinuation;
import datadog.context.ContextScope;
import datadog.trace.api.GenericClassValue;
import datadog.trace.api.InstrumenterConfig;
import datadog.trace.api.Platform;
import datadog.trace.bootstrap.ContextStore;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * This class is a helper for the ThreadPoolExecutorInstrumentation. The instrumentation has two
 * modes, where the legacy mode uses wrapping of the Runnable and the new mode uses the State
 * context store field in the actual Runnable. The ThreadLocal below is needed to transport the
 * ContextScope between two methods when using the context store approach. More details can be found
 * in the ThreadPoolExecutorInstrumentation.
 */
public final class TPEHelper {
  // If legacy is enabled, we will try to propagate via wrapping, if not we will try to propagate
  // via storing the state in the existing field in the Runnable
  private static final boolean useWrapping;
  // A ThreadPoolExecutor with one of these types will newer be propagated/wrapped
  private static final Set<String> excludedClasses;
  // A ThreadLocal to store the Scope between beforeExecute and afterExecute if wrapping is not used
  private static final ThreadLocal<ContextScope> threadLocalScope;

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

  private static final ClassValue<Boolean> PROPAGATE =
      GenericClassValue.of(input -> !excludedClasses.contains(input.getName()));

  public static boolean shouldPropagate(ThreadPoolExecutor executor) {
    // avoid tracking threads when building native images as it confuses the scanner
    // (we still want instrumentation applied, so tracking works in the built image)
    return !Platform.isNativeImageBuilder()
        && executor != null
        && PROPAGATE.get(executor.getClass());
  }

  public static Runnable captureOrWrap(
      ContextStore<Runnable, State> contextStore,
      Runnable task,
      Context context,
      ThreadPoolExecutor executor) {
    if (task != null && !exclude(RUNNABLE, task) && shouldCapture(context)) {
      State state = contextStore.getOrCreate(task, State.FACTORY);
      if (state.captureAndSetTpeContinuation(context) == null) {
        return canWrapCollision(executor.getQueue()) ? Wrapper.wrap(task, context) : null;
      }
    }
    return task;
  }

  /**
   * Retains the historical best-effort propagation for subclass overrides outside JDK admission.
   */
  public static void captureLegacy(ContextStore<Runnable, State> contextStore, Runnable task) {
    if (task != null && !exclude(RUNNABLE, task)) {
      AdviceUtils.capture(contextStore, task);
    }
  }

  private static boolean canWrapCollision(BlockingQueue<Runnable> queue) {
    return queue instanceof ArrayBlockingQueue
        || queue instanceof LinkedBlockingQueue
        || queue instanceof LinkedBlockingDeque
        || queue instanceof LinkedTransferQueue
        || queue instanceof SynchronousQueue;
  }

  public static ContextScope startScope(ContextStore<Runnable, State> contextStore, Runnable task) {
    if (task == null || exclude(RUNNABLE, task)) {
      return null;
    }
    State state = contextStore.get(task);
    return AdviceUtils.startTpeTaskScope(state);
  }

  public static void setThreadLocalScope(ContextScope scope, Runnable task) {
    if (scope == null || task == null || exclude(RUNNABLE, task)) {
      return;
    }
    ContextScope current = threadLocalScope.get();
    if (current != null) {
      current.close();
    }
    threadLocalScope.set(scope);
  }

  public static ContextScope getAndClearThreadLocalScope(Runnable task) {
    if (task == null || exclude(RUNNABLE, task)) {
      return null;
    }
    ContextScope scope = threadLocalScope.get();
    // Intentionally use `.set(null)` instead of `.remove()` for performance reasons.
    // For details see: https://github.com/DataDog/dd-trace-java/pull/9856#discussion_r2527729963
    // noinspection ThreadLocalSetWithNull
    threadLocalScope.set(null);
    return scope;
  }

  public static void endScope(ContextScope scope, Runnable task) {
    if (task == null || exclude(RUNNABLE, task)) {
      return;
    }
    AdviceUtils.endTaskScope(scope);
  }

  public static final class RejectedTask {
    private final Wrapper<?> wrapper;
    private final ContextContinuation continuation;
    private final ContextScope scope;

    public RejectedTask(Wrapper<?> wrapper, ContextContinuation continuation) {
      this.wrapper = wrapper;
      this.continuation = continuation;
      this.scope =
          wrapper != null
              ? wrapper.activate()
              : continuation == null ? null : continuation.resume();
    }

    public void close() {
      try {
        if (scope != null) {
          scope.close();
        }
      } finally {
        if (wrapper != null) {
          wrapper.cancel();
        } else if (continuation != null) {
          continuation.release();
        }
      }
    }
  }

  public static ContextContinuation prepareRejectedTask(
      ContextStore<Runnable, State> contextStore, Runnable task) {
    if (task == null || exclude(RUNNABLE, task)) {
      return null;
    }
    State state = contextStore.get(task);
    if (state == null) {
      return null;
    }
    State.TpeContinuation tpeContinuation = state.getAndResetTpeContinuation();
    if (tpeContinuation != null) {
      tpeContinuation.stopTiming();
      return tpeContinuation;
    }
    ContextContinuation continuation = state.getAndResetContinuation();
    if (continuation != null) {
      state.stopTiming();
    }
    return continuation;
  }
}

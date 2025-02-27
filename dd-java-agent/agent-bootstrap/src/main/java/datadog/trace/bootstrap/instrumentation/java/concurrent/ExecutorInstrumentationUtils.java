package datadog.trace.bootstrap.instrumentation.java.concurrent;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.isAsyncPropagationEnabled;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType;

import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Utils for concurrent instrumentations. */
public final class ExecutorInstrumentationUtils {

  private static final Logger log = LoggerFactory.getLogger(ExecutorInstrumentationUtils.class);

  /**
   * Checks if given task should get state attached.
   *
   * @param task task object
   * @param executor executor this task was scheduled on
   * @return true iff given task object should be wrapped
   */
  public static boolean shouldAttachStateToTask(final Object task, final Executor executor) {
    if (task == null) {
      return false;
    }
    if (ExcludeFilter.exclude(ExcludeType.EXECUTOR, task)) {
      return false;
    }
    return activeSpan() != null && isAsyncPropagationEnabled();
  }

  /**
   * Create task state given current span.
   *
   * @param contextStore context storage
   * @param task task instance
   * @param span current span
   * @param <T> task class type
   * @return new state
   */
  public static <T> State setupState(
      final ContextStore<T, State> contextStore, final T task, final AgentSpan span) {

    final State state = contextStore.putIfAbsent(task, State.FACTORY);

    if (!state.captureAndSetContinuation(span)) {
      log.debug(
          "continuation was already set for {} in span {}, no continuation captured.", task, span);
    }

    return state;
  }

  /**
   * Clean up after job submission method has exited.
   *
   * @param executor the current executor
   * @param state task instrumentation state
   * @param throwable throwable that may have been thrown
   */
  public static void cleanUpOnMethodExit(
      final Executor executor, final State state, final Throwable throwable) {
    if (null != state && null != throwable) {
      /*
      Note: this may potentially close somebody else's continuation if we didn't set it
      up in setupState because it was already present before us. This should be safe but
      may lead to non-attributed async work in some very rare cases.
      Alternative is to not close continuation here if we did not set it up in setupState
      but this may potentially lead to memory leaks if callers do not properly handle
      exceptions.
       */
      state.closeContinuation();
    }
  }

  private ExecutorInstrumentationUtils() {}
}

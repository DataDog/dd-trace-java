package datadog.trace.bootstrap.instrumentation.java.concurrent;

import static datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils.shouldCapture;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType;

import datadog.context.Context;
import datadog.trace.bootstrap.ContextStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Utils for concurrent instrumentations. */
public final class ExecutorInstrumentationUtils {

  private static final Logger log = LoggerFactory.getLogger(ExecutorInstrumentationUtils.class);

  /**
   * Checks if given task should get state attached.
   *
   * @param task task object
   * @param context active context
   * @return true iff given task object should be wrapped
   */
  public static boolean shouldAttachStateToTask(final Object task, final Context context) {
    if (task == null) {
      return false;
    }
    if (ExcludeFilter.exclude(ExcludeType.EXECUTOR, task)) {
      return false;
    }
    return shouldCapture(context);
  }

  /**
   * Create task state given current context.
   *
   * @param contextStore context storage
   * @param task task instance
   * @param context current context
   * @param <T> task class type
   * @return new state
   */
  public static <T> State setupState(
      final ContextStore<T, State> contextStore, final T task, final Context context) {
    final State state = contextStore.putIfAbsent(task, State.FACTORY);
    if (!state.captureAndSetContinuation(context)) {
      log.debug(
          "continuation was already set for {} in context {}, no continuation captured.",
          task,
          context);
    }
    return state;
  }

  /**
   * Clean up after job submission method has exited.
   *
   * @param state task instrumentation state
   * @param throwable throwable that may have been thrown
   */
  public static void cleanUpOnMethodExit(final State state, final Throwable throwable) {
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

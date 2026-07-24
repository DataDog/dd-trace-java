package datadog.trace.bootstrap.instrumentation.java.concurrent;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.isAsyncPropagationEnabled;

import datadog.context.Context;
import datadog.context.ContextContinuation;
import datadog.context.ContextScope;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;

/** Helper utils for Runnable/Callable instrumentation */
public class AdviceUtils {

  /**
   * Start scope for a given task
   *
   * @param contextStore context storage for task's state
   * @param task task to start scope for
   * @param <T> task's type
   * @return scope if scope was started, or null
   */
  public static <T> ContextScope startTaskScope(
      final ContextStore<T, State> contextStore, final T task) {
    return startTaskScope(contextStore.get(task));
  }

  public static ContextScope startTaskScope(State state) {
    if (state != null) {
      final ContextContinuation continuation = state.getAndResetContinuation();
      if (continuation != null) {
        final ContextScope scope = continuation.resume();
        // important - stop timing after the scope has been activated so the time in the queue can
        // be attributed to the correct context without duplicating the propagated information
        state.stopTiming();
        return scope;
      }
    }
    return null;
  }

  public static void endTaskScope(final ContextScope scope) {
    if (null != scope) {
      scope.close();
    }
  }

  public static <T> void cancelTask(ContextStore<T, State> contextStore, final T task) {
    State state = contextStore.get(task);
    if (null != state) {
      state.closeContinuation();
    }
  }

  public static boolean shouldCapture(Context context) {
    if (context == Context.root()) {
      return false;
    }
    AgentSpan span = AgentSpan.fromContext(context);
    // propagate contexts with no span or a valid span, when flag is on
    return (span == null || span.isValid()) && isAsyncPropagationEnabled();
  }

  public static <T> void capture(ContextStore<T, State> contextStore, T task) {
    Context context = Context.current();
    if (shouldCapture(context)) {
      State state = contextStore.get(task);
      if (null == state) {
        state = State.FACTORY.create();
        contextStore.put(task, state);
      }
      state.captureAndSetContinuation(context);
    }
  }
}

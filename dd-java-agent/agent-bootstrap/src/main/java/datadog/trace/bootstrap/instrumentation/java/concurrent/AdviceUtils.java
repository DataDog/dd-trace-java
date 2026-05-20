package datadog.trace.bootstrap.instrumentation.java.concurrent;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.isAsyncPropagationEnabled;

import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AsyncProfiledTaskHandoff;

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
  public static <T> AgentScope startTaskScope(
      final ContextStore<T, State> contextStore, final T task) {
    return startTaskScope(contextStore.get(task));
  }

  public static <T> AgentScope startTaskScopeWithActivationHandoff(
      final ContextStore<T, State> contextStore, final T task) {
    return startTaskScopeWithActivationHandoff(contextStore.get(task));
  }

  public static AgentScope startTaskScope(State state) {
    return startTaskScope(state, false);
  }

  public static AgentScope startTaskScopeWithActivationHandoff(State state) {
    return startTaskScope(state, true);
  }

  private static AgentScope startTaskScope(State state, boolean publishActivationNano) {
    if (state != null) {
      final AgentScope.Continuation continuation = state.getAndResetContinuation();
      if (continuation != null) {
        final AgentScope scope = continuation.activate();
        long activationStartNano = 0L;
        if (publishActivationNano) {
          activationStartNano = System.nanoTime();
          AsyncProfiledTaskHandoff.setPendingActivationStartNano(activationStartNano);
        }
        // important - stop timing after the scope has been activated so the time in the queue can
        // be attributed to the correct context without duplicating the propagated information
        state.stopTiming(activationStartNano);
        return scope;
      }
    }
    return null;
  }

  public static void endTaskScope(final AgentScope scope) {
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

  public static <T> void capture(ContextStore<T, State> contextStore, T task) {
    AgentSpan span = activeSpan();
    if (span != null && span.isValid() && isAsyncPropagationEnabled()) {
      State state = contextStore.get(task);
      if (null == state) {
        state = State.FACTORY.create();
        contextStore.put(task, state);
      }
      state.captureAndSetContinuation(span);
    }
  }
}

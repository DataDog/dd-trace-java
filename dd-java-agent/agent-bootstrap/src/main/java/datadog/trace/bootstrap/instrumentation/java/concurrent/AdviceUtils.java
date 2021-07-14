package datadog.trace.bootstrap.instrumentation.java.concurrent;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeScope;

import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.context.TraceScope;

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
  public static <T> TraceScope startTaskScope(
      final ContextStore<T, State> contextStore, final T task) {
    final State state = contextStore.get(task);
    if (state != null) {
      final TraceScope.Continuation continuation = state.getAndResetContinuation();
      if (continuation != null) {
        final TraceScope scope = continuation.activate();
        scope.setAsyncPropagation(true);
        return scope;
      }
    }
    return null;
  }

  public static void endTaskScope(final TraceScope scope) {
    if (scope instanceof AgentScope) {
      AgentScope agentScope = (AgentScope) scope;
      if (agentScope.checkpointed()) {
        agentScope.span().finishWork();
      }
    }
    if (scope != null) {
      scope.close();
    }
  }

  public static <T> void cancelTask(ContextStore<T, State> contextStore, final T task) {
    State state = contextStore.get(task);
    if (null != state) {
      state.closeContinuation();
    }
  }

  public static <T> void capture(
      ContextStore<T, State> contextStore, T task, boolean startThreadMigration) {
    TraceScope activeScope = activeScope();
    if (null != activeScope) {
      State state = contextStore.get(task);
      if (null == state) {
        state = State.FACTORY.create();
        contextStore.put(task, state);
      }
      if (state.captureAndSetContinuation(activeScope) && startThreadMigration) {
        state.startThreadMigration();
      }
    }
  }
}

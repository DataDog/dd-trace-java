package datadog.trace.bootstrap.instrumentation.java.concurrent;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeScope;

import datadog.trace.bootstrap.ContextStore;
import datadog.trace.context.TraceScope;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executor;
import lombok.extern.slf4j.Slf4j;

/** Utils for concurrent instrumentations. */
@Slf4j
public final class ExecutorInstrumentationUtils {

  private static boolean skipAttach(Object instance) {
    return SKIP_ATTACH.get(instance.getClass());
  }

  private static final ClassValue<Boolean> SKIP_ATTACH =
      new ClassValue<Boolean>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
          return selfContainedClasses.contains(type.getName());
        }
      };

  // TODO add config here for different instrumentations
  private static final Set<String> selfContainedClasses;

  static {
    selfContainedClasses = new HashSet<>();
    String[] classes = {
      "java.util.concurrent.CompletableFuture$UniCompletion",
      "java.util.concurrent.CompletableFuture$UniApply",
      "java.util.concurrent.CompletableFuture$UniAccept",
      "java.util.concurrent.CompletableFuture$UniRun",
      "java.util.concurrent.CompletableFuture$UniWhenComplete",
      "java.util.concurrent.CompletableFuture$UniHandle",
      "java.util.concurrent.CompletableFuture$UniExceptionally",
      "java.util.concurrent.CompletableFuture$UniComposeExceptionally",
      "java.util.concurrent.CompletableFuture$UniRelay",
      "java.util.concurrent.CompletableFuture$UniCompose",
      "java.util.concurrent.CompletableFuture$BiCompletion",
      // This is not a subclass of UniCompletion and doesn't have a dependent CompletableFuture
      // "java.util.concurrent.CompletableFuture$CoCompletion",
      "java.util.concurrent.CompletableFuture$BiApply",
      "java.util.concurrent.CompletableFuture$BiAccept",
      "java.util.concurrent.CompletableFuture$BiRun",
      "java.util.concurrent.CompletableFuture$BiRelay",
      "java.util.concurrent.CompletableFuture$OrApply",
      "java.util.concurrent.CompletableFuture$OrAccept",
      "java.util.concurrent.CompletableFuture$OrRun",
      // This is not a subclass of UniCompletion and doesn't have a dependent CompletableFuture
      // "java.util.concurrent.CompletableFuture$AnyOf",
      // This is not a subclass of UniCompletion and doesn't have a dependent CompletableFuture
      // "java.util.concurrent.CompletableFuture$Signaller",
    };
    selfContainedClasses.addAll(Arrays.asList(classes));
  }

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

    if (skipAttach(task)) {
      return false;
    }

    final TraceScope scope = activeScope();
    final Class enclosingClass = task.getClass().getEnclosingClass();

    return scope != null
        && scope.isAsyncPropagating()

        // Don't instrument the executor's own runnables.  These runnables may never return until
        // netty shuts down.  Any created continuations will be open until that time preventing
        // traces from being reported
        && (enclosingClass == null
            || !enclosingClass
                .getName()
                .equals("io.netty.util.concurrent.SingleThreadEventExecutor"));
  }

  /**
   * Create task state given current scope.
   *
   * @param contextStore context storage
   * @param task task instance
   * @param scope current scope
   * @param <T> task class type
   * @return new state
   */
  public static <T> State setupState(
      final ContextStore<T, State> contextStore, final T task, final TraceScope scope) {

    final State state = contextStore.putIfAbsent(task, State.FACTORY);

    final TraceScope.Continuation continuation = scope.capture();
    if (state.setContinuation(continuation)) {
      if (log.isDebugEnabled()) {
        log.debug("created continuation {} from scope {}, state: {}", continuation, scope, state);
      }
    } else {
      continuation.cancel();
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

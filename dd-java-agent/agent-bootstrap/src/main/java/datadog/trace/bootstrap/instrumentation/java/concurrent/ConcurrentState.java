package datadog.trace.bootstrap.instrumentation.java.concurrent;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeScope;

import datadog.trace.bootstrap.ContextStore;
import datadog.trace.context.TraceScope;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class ConcurrentState {

  public static ContextStore.Factory<ConcurrentState> FACTORY =
      new ContextStore.Factory<ConcurrentState>() {
        @Override
        public ConcurrentState create() {
          return new ConcurrentState();
        }
      };

  private final AtomicReference<TraceScope.Continuation> continuationRef =
      new AtomicReference<>(null);

  private ConcurrentState() {}

  public static <K> void captureActiveScope(ContextStore<K, ConcurrentState> contextStore, K key) {
    final TraceScope scope = activeScope();
    captureScope(contextStore, key, scope);
  }

  public static <K> void captureScope(
      ContextStore<K, ConcurrentState> contextStore, K key, TraceScope scope) {
    final ConcurrentState state = contextStore.putIfAbsent(key, FACTORY);
    if (scope != null) {
      final TraceScope.Continuation continuation = scope.captureConcurrent();
      if (continuation != null) {
        state.setContinuation(continuation);
      }
    }
  }

  public static <K> TraceScope activateAndContinueContinuation(
      ContextStore<K, ConcurrentState> contextStore, K key) {
    final ConcurrentState state = contextStore.get(key);
    if (state == null) {
      return null;
    }
    return state.activateAndContinueContinuation();
  }

  public static <K> void closeScope(
      ContextStore<K, ConcurrentState> contextStore, K key, TraceScope scope, Throwable throwable) {
    final ConcurrentState state = contextStore.get(key);
    if (scope != null) {
      scope.close();
      return;
    }
    if (state == null) {
      return;
    }
    if (throwable != null) {
      // This might lead to the continuation being consumed early, but it's better to be safe if we
      // threw an Exception on entry
      state.closeContinuation();
    }
  }

  public static <K> void closeAndClearContinuation(
      ContextStore<K, ConcurrentState> contextStore, K key) {
    final ConcurrentState state = contextStore.get(key);
    if (state == null) {
      return;
    }
    state.closeAndClearContinuation();
  }

  boolean setContinuation(TraceScope.Continuation continuation) {
    final boolean result = continuationRef.compareAndSet(null, continuation);
    assert result
        : "Losing the race to set the continuation correlates with bugs. "
            + continuationRef.get()
            + " beat "
            + continuation;
    if (!result && log.isDebugEnabled()) {
      log.debug(
          "Failed to set continuation because another continuation is already set {}: new: {}, old: {}",
          this,
          continuation,
          continuationRef.get());
    }
    return result;
  }

  TraceScope activateAndContinueContinuation() {
    final TraceScope.Continuation continuation = continuationRef.get();
    if (continuation != null) {
      return continuation.activate();
    }
    return null;
  }

  void closeContinuation() {
    final TraceScope.Continuation continuation = continuationRef.get();
    if (continuation != null) {
      continuation.cancel();
    }
  }

  void closeAndClearContinuation() {
    final TraceScope.Continuation continuation = continuationRef.getAndSet(null);
    if (continuation != null) {
      continuation.cancel();
    }
  }
}

package datadog.trace.bootstrap.instrumentation.java.concurrent;

import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.context.TraceScope;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;

/**
 * The {@link ConcurrentState} models a {@link State} where there can be multiple threads racing to
 * activate spans and close them all from the same continuation. Only one thread will actually
 * succeed and do meaningful work in that span, and then close the span and continuation properly.
 */
@Slf4j
public final class ConcurrentState {

  private static final class ContinuationClaim implements TraceScope.Continuation {

    @Override
    public TraceScope activate() {
      throw new IllegalStateException();
    }

    @Override
    public void cancel() {
      throw new IllegalStateException();
    }
  }

  private static final TraceScope.Continuation CLAIMED = new ContinuationClaim();

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

  public static <K> void captureScope(
      ContextStore<K, ConcurrentState> contextStore, K key, TraceScope scope) {
    final ConcurrentState state = contextStore.putIfAbsent(key, FACTORY);
    if (!state.captureAndSetContinuation(scope) && log.isDebugEnabled()) {
      log.debug(
          "continuation was already set for {} in scope {}, no continuation captured.", key, scope);
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

  private boolean captureAndSetContinuation(final TraceScope scope) {
    if (continuationRef.compareAndSet(null, CLAIMED)) {
      // lazy write is guaranteed to be seen by getAndSet
      if (scope != null) {
        continuationRef.lazySet(scope.captureConcurrent());
      } else {
        continuationRef.lazySet(null);
      }
      return true;
    }
    return false;
  }

  private TraceScope activateAndContinueContinuation() {
    final TraceScope.Continuation continuation = continuationRef.get();
    if (continuation != null && continuation != CLAIMED) {
      return continuation.activate();
    } else if (continuation == null) {
      return AgentTracer.activateSpan(AgentTracer.noopSpan());
    }
    return null;
  }

  private void closeContinuation() {
    final TraceScope.Continuation continuation = continuationRef.get();
    if (continuation != null && continuation != CLAIMED) {
      continuation.cancel();
    }
  }

  private void closeAndClearContinuation() {
    final TraceScope.Continuation continuation = continuationRef.get();
    if (continuation != null && continuation != CLAIMED) {
      // We should never be able to reuse this state
      continuationRef.compareAndSet(continuation, CLAIMED);
      continuation.cancel();
    }
  }
}

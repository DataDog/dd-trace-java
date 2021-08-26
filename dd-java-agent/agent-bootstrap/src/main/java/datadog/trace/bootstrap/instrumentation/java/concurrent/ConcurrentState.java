package datadog.trace.bootstrap.instrumentation.java.concurrent;

import static datadog.trace.bootstrap.instrumentation.java.concurrent.ContinuationClaim.CLAIMED;

import datadog.trace.bootstrap.ContextStore;
import datadog.trace.context.TraceScope;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link ConcurrentState} models a {@link State} where there can be multiple threads racing to
 * activate spans and close them all from the same continuation. Only one thread will actually
 * succeed and do meaningful work in that span, and then close the span and continuation properly.
 */
public final class ConcurrentState {

  private static final Logger log = LoggerFactory.getLogger(ConcurrentState.class);

  public static ContextStore.Factory<ConcurrentState> FACTORY =
      new ContextStore.Factory<ConcurrentState>() {
        @Override
        public ConcurrentState create() {
          return new ConcurrentState();
        }
      };

  private volatile TraceScope.Continuation continuation = null;

  private static final AtomicReferenceFieldUpdater<ConcurrentState, TraceScope.Continuation>
      CONTINUATION =
          AtomicReferenceFieldUpdater.newUpdater(
              ConcurrentState.class, TraceScope.Continuation.class, "continuation");

  private ConcurrentState() {}

  public static <K> ConcurrentState captureScope(
      ContextStore<K, ConcurrentState> contextStore, K key, TraceScope scope) {
    if (scope != null) {
      final ConcurrentState state = contextStore.putIfAbsent(key, FACTORY);
      if (!state.captureAndSetContinuation(scope) && log.isDebugEnabled()) {
        log.debug(
            "continuation was already set for {} in scope {}, no continuation captured.",
            key,
            scope);
      }
      return state;
    }
    return null;
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
    if (CONTINUATION.compareAndSet(this, null, CLAIMED)) {
      // lazy write is guaranteed to be seen by getAndSet
      CONTINUATION.lazySet(this, scope.captureConcurrent());
      return true;
    }
    return false;
  }

  private TraceScope activateAndContinueContinuation() {
    final TraceScope.Continuation continuation = CONTINUATION.get(this);
    if (continuation != null && continuation != CLAIMED) {
      return continuation.activate();
    }
    return null;
  }

  private void closeContinuation() {
    final TraceScope.Continuation continuation = CONTINUATION.get(this);
    if (continuation != null && continuation != CLAIMED) {
      continuation.cancel();
    }
  }

  private void closeAndClearContinuation() {
    final TraceScope.Continuation continuation = CONTINUATION.get(this);
    if (continuation != null && continuation != CLAIMED) {
      // We should never be able to reuse this state
      CONTINUATION.compareAndSet(this, continuation, CLAIMED);
      continuation.cancel();
    }
  }
}

package datadog.trace.bootstrap.instrumentation.java.concurrent;

import static datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils.shouldCapture;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ContinuationClaim.CLAIMED;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ContinuationClaim.TERMINATED;

import datadog.context.Context;
import datadog.context.ContextContinuation;
import datadog.context.ContextScope;
import datadog.trace.bootstrap.ContextStore;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link ConcurrentState} models a {@link State} where there can be multiple threads racing to
 * activate spans and close them all from the same continuation. Only one thread will actually
 * succeed and do meaningful work in that span, and then close the span and continuation properly.
 */
public final class ConcurrentState {

  private static final Logger log = LoggerFactory.getLogger(ConcurrentState.class);

  public static ContextStore.Factory<ConcurrentState> FACTORY = ConcurrentState::new;

  private volatile ContextContinuation continuation = null;

  private static final AtomicReferenceFieldUpdater<ConcurrentState, ContextContinuation>
      CONTINUATION =
          AtomicReferenceFieldUpdater.newUpdater(
              ConcurrentState.class, ContextContinuation.class, "continuation");

  private ConcurrentState() {}

  @Nullable
  public static <K> ConcurrentState captureContinuation(
      ContextStore<K, ConcurrentState> contextStore, K key, Context context) {
    if (shouldCapture(context)) {
      final ConcurrentState state = contextStore.getOrCreate(key, FACTORY);
      if (!state.captureAndSetContinuation(context) && log.isDebugEnabled()) {
        log.debug(
            "continuation was already set for {} in context {}, no continuation captured.",
            key,
            context);
      }
      return state;
    } else {
      return null;
    }
  }

  @Nullable
  public static <K> ContextScope activateAndContinueContinuation(
      ContextStore<K, ConcurrentState> contextStore, K key) {
    final ConcurrentState state = contextStore.get(key);
    if (state == null) {
      return null;
    }
    return state.activateAndContinueContinuation();
  }

  public static <K> void closeScope(
      ContextStore<K, ConcurrentState> contextStore,
      K key,
      ContextScope scope,
      Throwable throwable) {
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
      state.cancelContinuation();
    }
  }

  public static <K> void cancelAndClearContinuation(
      ContextStore<K, ConcurrentState> contextStore, K key) {
    final ConcurrentState state = contextStore.get(key);
    if (state == null) {
      return;
    }
    state.cancelAndClearContinuation();
  }

  boolean captureAndSetContinuation(final Context context) {
    if (CONTINUATION.compareAndSet(this, null, CLAIMED)) {
      final ContextContinuation continuation = context.capture().hold();
      if (CONTINUATION.compareAndSet(this, CLAIMED, continuation)) {
        return true;
      }
      continuation.release();
    }
    return false;
  }

  private ContextScope activateAndContinueContinuation() {
    return activateAndContinueContinuation(CONTINUATION.get(this));
  }

  synchronized ContextScope activateAndContinueContinuation(
      final ContextContinuation continuation) {
    if (isLive(continuation) && CONTINUATION.get(this) == continuation) {
      return continuation.resume();
    }
    return null;
  }

  private void cancelContinuation() {
    cancelAndClearContinuation();
  }

  void cancelAndClearContinuation() {
    final ContextContinuation continuation;
    synchronized (this) {
      continuation = CONTINUATION.getAndSet(this, TERMINATED);
    }
    if (isLive(continuation)) {
      continuation.release();
    }
  }

  private static boolean isLive(final ContextContinuation continuation) {
    return continuation != null && continuation != CLAIMED && continuation != TERMINATED;
  }
}

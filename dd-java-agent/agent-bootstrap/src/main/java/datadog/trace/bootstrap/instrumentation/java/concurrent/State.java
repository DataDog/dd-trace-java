package datadog.trace.bootstrap.instrumentation.java.concurrent;

import static datadog.trace.bootstrap.instrumentation.java.concurrent.ContinuationClaim.CLAIMED;

import datadog.context.Context;
import datadog.context.ContextContinuation;
import datadog.trace.api.profiling.Timing;
import datadog.trace.bootstrap.ContextStore;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import javax.annotation.Nullable;

public final class State {

  public static ContextStore.Factory<State> FACTORY = State::new;

  private static final AtomicReferenceFieldUpdater<State, ContextContinuation> CONTINUATION =
      AtomicReferenceFieldUpdater.newUpdater(
          State.class, ContextContinuation.class, "continuation");

  private volatile ContextContinuation continuation = null;

  private static final AtomicReferenceFieldUpdater<State, Timing> TIMING =
      AtomicReferenceFieldUpdater.newUpdater(State.class, Timing.class, "timing");

  private volatile Timing timing = null;

  private State() {}

  public boolean captureAndSetContinuation(final Context context) {
    if (CONTINUATION.compareAndSet(this, null, CLAIMED)) {
      // it's a real pain to do this twice, and this can actually
      // happen systematically - WITHOUT RACES - because of broken
      // instrumentation, e.g. SetExecuteRunnableStateAdvice
      // "double instruments" calls to ScheduledExecutorService.submit/schedule
      //
      // lazy write is guaranteed to be seen by getAndSet
      CONTINUATION.lazySet(this, context.capture());
      return true;
    }
    return false;
  }

  public boolean setOrCancelContinuation(final ContextContinuation continuation) {
    if (CONTINUATION.compareAndSet(this, null, CLAIMED)) {
      // lazy write is guaranteed to be seen by getAndSet
      CONTINUATION.lazySet(this, continuation);
      return true;
    } else {
      continuation.release();
      return false;
    }
  }

  public void closeContinuation() {
    ContextContinuation continuation = getAndResetContinuation();
    if (null != continuation) {
      continuation.release();
    }
  }

  public Context getContext() {
    ContextContinuation continuation = CONTINUATION.get(this);
    if (null == continuation || CLAIMED == continuation) {
      return Context.root();
    }
    return continuation.context();
  }

  @Nullable
  public ContextContinuation getAndResetContinuation() {
    ContextContinuation continuation = CONTINUATION.get(this);
    if (null == continuation || CLAIMED == continuation) {
      return null;
    }
    CONTINUATION.compareAndSet(this, continuation, null);
    return continuation;
  }

  public void setTiming(Timing timing) {
    TIMING.lazySet(this, timing);
  }

  public boolean isTimed() {
    return TIMING.get(this) != null;
  }

  public void stopTiming() {
    Timing timing = TIMING.getAndSet(this, null);
    if (timing != null) {
      QueueTimerHelper.stopQueuingTimer(timing);
    }
  }
}

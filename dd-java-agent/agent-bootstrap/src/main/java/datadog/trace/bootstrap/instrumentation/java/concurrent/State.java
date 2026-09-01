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

  /**
   * Captures context for an unwrapped {@code ThreadPoolExecutor} submission.
   *
   * <p>The tagged continuation can only be consumed by {@code beforeExecute}; regular Runnable
   * advice deliberately ignores it. This prevents a direct invocation, or another submission of the
   * same Runnable, from stealing the queued submission's context.
   */
  @Nullable
  public TpeContinuation captureAndSetTpeContinuation(final Context context) {
    while (true) {
      ContextContinuation current = CONTINUATION.get(this);
      if (current == null) {
        if (!CONTINUATION.compareAndSet(this, null, CLAIMED)) {
          continue;
        }
        try {
          TpeContinuation continuation = new TpeContinuation(context.capture());
          CONTINUATION.lazySet(this, continuation);
          return continuation;
        } catch (Throwable error) {
          CONTINUATION.compareAndSet(this, CLAIMED, null);
          throw error;
        }
      }
      if (current == CLAIMED || current instanceof TpeContinuation) {
        return null;
      }
      // Generic Executor advice can run before ThreadPoolExecutor advice for the same call.
      // Transfer
      // that continuation instead of treating the duplicate instrumentation as task reuse.
      if (current.context() != context) {
        return null;
      }
      TpeContinuation continuation = new TpeContinuation(current);
      if (CONTINUATION.compareAndSet(this, current, continuation)) {
        return continuation;
      }
    }
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

  @Nullable
  public ContextContinuation getContinuation() {
    ContextContinuation continuation = CONTINUATION.get(this);
    return continuation == CLAIMED || continuation instanceof TpeContinuation ? null : continuation;
  }

  @Nullable
  public ContextContinuation getCancellableContinuation() {
    ContextContinuation continuation = CONTINUATION.get(this);
    return continuation == CLAIMED ? null : continuation;
  }

  public void closeContinuation(ContextContinuation expected) {
    if (expected != null && CONTINUATION.compareAndSet(this, expected, null)) {
      if (expected instanceof TpeContinuation) {
        ((TpeContinuation) expected).cancel();
      } else {
        expected.release();
      }
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
    while (true) {
      ContextContinuation continuation = CONTINUATION.get(this);
      if (null == continuation
          || CLAIMED == continuation
          || continuation instanceof TpeContinuation) {
        return null;
      }
      if (CONTINUATION.compareAndSet(this, continuation, null)) {
        return continuation;
      }
    }
  }

  @Nullable
  public TpeContinuation getAndResetTpeContinuation() {
    while (true) {
      ContextContinuation continuation = CONTINUATION.get(this);
      if (!(continuation instanceof TpeContinuation)) {
        return null;
      }
      if (CONTINUATION.compareAndSet(this, continuation, null)) {
        return (TpeContinuation) continuation;
      }
    }
  }

  @Nullable
  public TpeContinuation getTpeContinuation() {
    ContextContinuation continuation = CONTINUATION.get(this);
    return continuation instanceof TpeContinuation ? (TpeContinuation) continuation : null;
  }

  public void closeTpeContinuation(TpeContinuation expected) {
    closeContinuation(expected);
  }

  public void setTiming(Timing timing) {
    TpeContinuation continuation = getTpeContinuation();
    if (continuation != null) {
      continuation.setTiming(timing);
    } else {
      TIMING.lazySet(this, timing);
    }
  }

  public boolean isTimed() {
    TpeContinuation continuation = getTpeContinuation();
    return TIMING.get(this) != null || (continuation != null && continuation.isTimed());
  }

  public void stopTiming() {
    Timing timing = TIMING.getAndSet(this, null);
    if (timing != null) {
      QueueTimerHelper.stopQueuingTimer(timing);
    }
  }

  public static final class TpeContinuation implements ContextContinuation {
    private final ContextContinuation delegate;
    private volatile Timing timing;

    private TpeContinuation(ContextContinuation delegate) {
      this.delegate = delegate;
    }

    @Override
    public ContextContinuation hold() {
      delegate.hold();
      return this;
    }

    @Override
    public Context context() {
      return delegate.context();
    }

    @Override
    public datadog.context.ContextScope resume() {
      return delegate.resume();
    }

    @Override
    public void release() {
      delegate.release();
    }

    private void setTiming(Timing timing) {
      this.timing = timing;
    }

    private boolean isTimed() {
      return timing != null;
    }

    public void stopTiming() {
      Timing timing = this.timing;
      this.timing = null;
      if (timing != null) {
        QueueTimerHelper.stopQueuingTimer(timing);
      }
    }

    private void cancel() {
      release();
      stopTiming();
    }
  }
}

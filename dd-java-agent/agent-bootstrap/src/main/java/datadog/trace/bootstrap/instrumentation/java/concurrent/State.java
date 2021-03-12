package datadog.trace.bootstrap.instrumentation.java.concurrent;

import datadog.trace.bootstrap.ContextStore;
import datadog.trace.context.TraceScope;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

public final class State {

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

  public static ContextStore.Factory<State> FACTORY =
      new ContextStore.Factory<State>() {
        @Override
        public State create() {
          return new State();
        }
      };

  private static final AtomicReferenceFieldUpdater<State, TraceScope.Continuation> CONTINUATION =
      AtomicReferenceFieldUpdater.newUpdater(
          State.class, TraceScope.Continuation.class, "continuation");

  private static final TraceScope.Continuation CLAIMED = new ContinuationClaim();

  private volatile TraceScope.Continuation continuation = null;

  private State() {}

  public boolean captureAndSetContinuation(final TraceScope scope) {
    if (CONTINUATION.compareAndSet(this, null, CLAIMED)) {
      // it's a real pain to do this twice, and this can actually
      // happen systematically - WITHOUT RACES - because of broken
      // instrumentation, e.g. SetExecuteRunnableStateAdvice
      // "double instruments" calls to ScheduledExecutorService.submit/schedule
      //
      // lazy write is guaranteed to be seen by getAndSet
      CONTINUATION.lazySet(this, scope.capture());
      return true;
    }
    return false;
  }

  public boolean setOrCancelContinuation(final TraceScope.Continuation continuation) {
    if (CONTINUATION.compareAndSet(this, null, CLAIMED)) {
      // lazy write is guaranteed to be seen by getAndSet
      CONTINUATION.lazySet(this, continuation);
      return true;
    } else {
      continuation.cancel();
      return false;
    }
  }

  public void closeContinuation() {
    TraceScope.Continuation continuation = getAndResetContinuation();
    if (null != continuation) {
      continuation.cancel();
    }
  }

  public TraceScope.Continuation getAndResetContinuation() {
    TraceScope.Continuation continuation = CONTINUATION.get(this);
    if (null == continuation || CLAIMED == continuation) {
      return null;
    }
    CONTINUATION.compareAndSet(this, continuation, null);
    return continuation;
  }
}

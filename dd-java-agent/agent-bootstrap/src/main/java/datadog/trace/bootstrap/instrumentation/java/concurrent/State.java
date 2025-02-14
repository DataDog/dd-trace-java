package datadog.trace.bootstrap.instrumentation.java.concurrent;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.captureSpan;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ContinuationClaim.CLAIMED;

import datadog.trace.api.profiling.Timing;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

public final class State {

  public static ContextStore.Factory<State> FACTORY = State::new;

  private static final AtomicReferenceFieldUpdater<State, AgentScope.Continuation> CONTINUATION =
      AtomicReferenceFieldUpdater.newUpdater(
          State.class, AgentScope.Continuation.class, "continuation");

  private volatile AgentScope.Continuation continuation = null;

  private static final AtomicReferenceFieldUpdater<State, Timing> TIMING =
      AtomicReferenceFieldUpdater.newUpdater(State.class, Timing.class, "timing");

  private volatile Timing timing = null;

  private State() {}

  public boolean captureAndSetContinuation(final AgentSpan span) {
    if (CONTINUATION.compareAndSet(this, null, CLAIMED)) {
      // it's a real pain to do this twice, and this can actually
      // happen systematically - WITHOUT RACES - because of broken
      // instrumentation, e.g. SetExecuteRunnableStateAdvice
      // "double instruments" calls to ScheduledExecutorService.submit/schedule
      //
      // lazy write is guaranteed to be seen by getAndSet
      CONTINUATION.lazySet(this, captureSpan(span));
      return true;
    }
    return false;
  }

  public boolean setOrCancelContinuation(final AgentScope.Continuation continuation) {
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
    AgentScope.Continuation continuation = getAndResetContinuation();
    if (null != continuation) {
      continuation.cancel();
    }
  }

  public AgentSpan getSpan() {
    AgentScope.Continuation continuation = CONTINUATION.get(this);
    if (null != continuation) {
      return continuation.span();
    }
    return null;
  }

  public AgentScope.Continuation getAndResetContinuation() {
    AgentScope.Continuation continuation = CONTINUATION.get(this);
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

package datadog.trace.bootstrap.instrumentation.java.concurrent;

import static datadog.trace.bootstrap.instrumentation.java.concurrent.ContinuationClaim.CLAIMED;

import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

public final class State {

  public static ContextStore.Factory<State> FACTORY =
      new ContextStore.Factory<State>() {
        @Override
        public State create() {
          return new State();
        }
      };

  private static final AtomicReferenceFieldUpdater<State, AgentScope.Continuation> CONTINUATION =
      AtomicReferenceFieldUpdater.newUpdater(
          State.class, AgentScope.Continuation.class, "continuation");

  private volatile AgentScope.Continuation continuation = null;

  private State() {}

  public boolean captureAndSetContinuation(final AgentScope scope) {
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
      return continuation.getSpan();
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

  public void startThreadMigration() {
    AgentScope.Continuation continuation = CONTINUATION.get(this);
    if (null != continuation) {
      continuation.migrate();
    }
  }
}

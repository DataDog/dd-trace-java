package datadog.trace.bootstrap.instrumentation.java.concurrent;

import datadog.trace.bootstrap.ContextStore;
import datadog.trace.context.TraceScope;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;

@Slf4j
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

  private static final TraceScope.Continuation CLAIMED = new ContinuationClaim();

  private final AtomicReference<TraceScope.Continuation> continuationRef =
      new AtomicReference<>(null);

  private State() {}

  public boolean captureAndSetContinuation(final TraceScope scope) {
    if (continuationRef.compareAndSet(null, CLAIMED)) {
      // it's a real pain to do this twice, and this can actually
      // happen systematically - WITHOUT RACES - because of broken
      // instrumentation, e.g. SetExecuteRunnableStateAdvice
      // "double instruments" calls to ScheduledExecutorService.submit/schedule
      //
      // lazy write is guaranteed to be seen by getAndSet
      continuationRef.lazySet(scope.capture());
      return true;
    }
    return false;
  }

  public void closeContinuation() {
    TraceScope.Continuation continuation = getAndResetContinuation();
    if (null != continuation) {
      continuation.cancel();
    }
  }

  public TraceScope.Continuation getAndResetContinuation() {
    // will either see null, CLAIMED, or a value we need to close here
    //
    // if this happens before the CAS in captureAndSetContinuation,
    // it prevents the CAS from succeeding and no continuation is created
    // if it happens after the CAS but before the write is issued, it is
    // as if we see null, but we prevent other writers from racing with the
    // last winner of the CAS, preventing a continuation from being created
    // without ever being closed. If this happens after the write is issued,
    // we are guaranteed to see it.
    TraceScope.Continuation continuation = continuationRef.getAndSet(CLAIMED);
    // we've captured the continuation in such a way writers can't race, let
    // one of them set the continuation now, unless there was a write since then
    continuationRef.compareAndSet(CLAIMED, null);
    return continuation == CLAIMED ? null : continuation;
  }
}

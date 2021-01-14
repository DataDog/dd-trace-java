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

  public boolean setOrCancelContinuation(final TraceScope.Continuation continuation) {
    if (continuationRef.compareAndSet(null, CLAIMED)) {
      // lazy write is guaranteed to be seen by getAndSet
      continuationRef.lazySet(continuation);
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
    TraceScope.Continuation continuation = continuationRef.get();
    if (null == continuation || CLAIMED == continuation) {
      return null;
    }
    continuationRef.compareAndSet(continuation, null);
    return continuation;
  }
}

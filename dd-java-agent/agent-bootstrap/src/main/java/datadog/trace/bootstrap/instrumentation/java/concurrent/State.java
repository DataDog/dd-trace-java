package datadog.trace.bootstrap.instrumentation.java.concurrent;

import datadog.trace.bootstrap.ContextStore;
import datadog.trace.context.TraceScope;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class State {

  public static ContextStore.Factory<State> FACTORY =
      new ContextStore.Factory<State>() {
        @Override
        public State create() {
          return new State();
        }
      };

  private final AtomicReference<TraceScope.Continuation> continuationRef =
      new AtomicReference<>(null);

  private State() {}

  public boolean setContinuation(final TraceScope.Continuation continuation) {
    final boolean result = continuationRef.compareAndSet(null, continuation);
    assert result
        : "Losing the race to set the continuation correlates with bugs. "
            + continuationRef.get()
            + " beat "
            + continuation;
    if (!result && log.isDebugEnabled()) {
      log.debug(
          "Failed to set continuation because another continuation is already set {}: new: {}, old: {}",
          this,
          continuation,
          continuationRef.get());
    }
    return result;
  }

  public void closeContinuation() {
    final TraceScope.Continuation continuation = continuationRef.getAndSet(null);
    if (continuation != null) {
      // We have opened this continuation, we shall not close parent scope when we close it,
      // otherwise owners of that scope will get confused.
      continuation.cancel();
    }
  }

  public TraceScope.Continuation getAndResetContinuation() {
    return continuationRef.getAndSet(null);
  }

  public TraceScope activateAndContinueContinuation() {
    TraceScope scope = null;
    while (scope == null) {
      TraceScope.Continuation tscCurrent = continuationRef.get();
      if (tscCurrent == null) {
        return null;
      }
      scope = tscCurrent.activateIfPossible();
      if (scope == null) {
        // Somebody else is activating right now, retry
        continue;
      }
      scope.setAsyncPropagation(true);
      TraceScope.Continuation tscNew = scope.capture();
      if (!continuationRef.compareAndSet(tscCurrent, tscNew)) {
        if (continuationRef.get() != null) {
          // If we got back anything but a null, there is a bad race and we should log
          if (log.isDebugEnabled()) {
            log.debug(
                "Failed to reactivate continuation because another continuation was set {}: new: {}, old: {}",
                scope,
                tscNew,
                continuationRef.get());
          }
        }
        // Cancel the new continuation since nobody will be using it
        tscNew.cancel();
      }
    }
    return scope;
  }

  public void cancelContinuationIfPossible() {
    final TraceScope.Continuation continuation = continuationRef.getAndSet(null);
    if (continuation != null) {
      continuation.cancelIfPossible();
    }
  }
}

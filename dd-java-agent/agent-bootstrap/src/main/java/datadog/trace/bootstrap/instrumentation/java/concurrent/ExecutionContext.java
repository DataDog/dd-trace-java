package datadog.trace.bootstrap.instrumentation.java.concurrent;

import datadog.trace.bootstrap.instrumentation.api.Pair;
import datadog.trace.context.TraceScope;
import java.lang.ref.WeakReference;
import java.util.concurrent.atomic.AtomicReference;

public final class ExecutionContext implements Runnable {

  private static final ThreadLocal<Pair<WeakReference<Runnable>, TraceScope>> ACTIVE_SCOPE =
      new ThreadLocal<>();

  public static void clearExecutionContext(Runnable finished) {
    Pair<WeakReference<Runnable>, TraceScope> activeScope = ACTIVE_SCOPE.get();
    if (null != activeScope) {
      Runnable expected = activeScope.getLeft().get();
      if (finished == expected
          || ((finished instanceof ExecutionContext)
              && ((ExecutionContext) finished).delegate == expected)) {
        ACTIVE_SCOPE.remove();
        activeScope.getRight().close();
      }
    }
  }

  public static ExecutionContext wrap(TraceScope scope, Runnable runnable) {
    return new ExecutionContext(scope.capture(), runnable);
  }

  private final AtomicReference<TraceScope.Continuation> continuation;
  private final Runnable delegate;

  ExecutionContext(TraceScope.Continuation continuation, Runnable delegate) {
    this.continuation = new AtomicReference<>(continuation);
    this.delegate = delegate;
  }

  public Runnable activateAndUnwrap() {
    TraceScope.Continuation snapshot = continuation.getAndSet(null);
    if (null != snapshot) {
      ACTIVE_SCOPE.set(Pair.of(new WeakReference<>(delegate), snapshot.activate()));
    }
    return delegate;
  }

  public void cancel() {
    TraceScope.Continuation snapshot = continuation.getAndSet(null);
    if (null != snapshot) {
      snapshot.cancel();
    }
  }

  @Override
  public void run() {
    TraceScope.Continuation snapshot = continuation.getAndSet(null);
    if (null != snapshot) {
      try (TraceScope scope = snapshot.activate()) {
        scope.setAsyncPropagation(true);
        delegate.run();
      }
    }
  }
}

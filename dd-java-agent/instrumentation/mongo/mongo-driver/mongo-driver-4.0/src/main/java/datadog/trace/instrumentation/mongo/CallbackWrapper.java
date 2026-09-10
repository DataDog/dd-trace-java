package datadog.trace.instrumentation.mongo;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.captureActiveSpan;

import com.mongodb.internal.async.SingleResultCallback;
import datadog.context.Context;
import datadog.context.ContextContinuation;
import datadog.context.ContextScope;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

public class CallbackWrapper<T> implements SingleResultCallback<Object> {
  private static final AtomicReferenceFieldUpdater<CallbackWrapper, ContextContinuation>
      CONTINUATION =
          AtomicReferenceFieldUpdater.newUpdater(
              CallbackWrapper.class, ContextContinuation.class, "continuation");

  private volatile ContextContinuation continuation = null;
  private final SingleResultCallback<Object> wrapped;

  public CallbackWrapper(ContextContinuation continuation, SingleResultCallback<Object> wrapped) {
    CONTINUATION.set(this, continuation);
    this.wrapped = wrapped;
  }

  @Override
  public void onResult(Object result, Throwable t) {
    ContextContinuation continuation = getAndResetContinuation();
    if (null != continuation) {
      ContextScope scope = continuation.resume();
      try {
        wrapped.onResult(result, t);
      } finally {
        scope.close();
      }
    } else {
      wrapped.onResult(result, t);
    }
  }

  private void cancel() {
    ContextContinuation continuation = getAndResetContinuation();
    if (null != continuation) {
      continuation.release();
    }
  }

  private ContextContinuation getAndResetContinuation() {
    ContextContinuation continuation = this.continuation;
    if (continuation != null) {
      if (CONTINUATION.compareAndSet(this, continuation, null)) {
        return continuation;
      }
    }
    return null;
  }

  public static SingleResultCallback<Object> wrapIfRequired(SingleResultCallback<Object> callback) {
    ContextContinuation continuation = captureActiveSpan();
    if (continuation.context() != Context.root()) {
      return new CallbackWrapper<>(continuation, callback);
    }
    return callback;
  }

  public static void cancel(SingleResultCallback<Object> callback) {
    if (callback instanceof CallbackWrapper) {
      CallbackWrapper<Object> wrapped = (CallbackWrapper<Object>) callback;
      wrapped.cancel();
    }
  }
}

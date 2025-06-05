package datadog.trace.instrumentation.mongo;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.captureActiveSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.noopContinuation;

import com.mongodb.internal.async.SingleResultCallback;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

public class CallbackWrapper<T> implements SingleResultCallback<Object> {
  private static final AtomicReferenceFieldUpdater<CallbackWrapper, AgentScope.Continuation>
      CONTINUATION =
          AtomicReferenceFieldUpdater.newUpdater(
              CallbackWrapper.class, AgentScope.Continuation.class, "continuation");

  private volatile AgentScope.Continuation continuation = null;
  private final SingleResultCallback<Object> wrapped;

  public CallbackWrapper(
      AgentScope.Continuation continuation, SingleResultCallback<Object> wrapped) {
    CONTINUATION.set(this, continuation);
    this.wrapped = wrapped;
  }

  @Override
  public void onResult(Object result, Throwable t) {
    AgentScope.Continuation continuation = getAndResetContinuation();
    if (null != continuation) {
      AgentScope scope = continuation.activate();
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
    AgentScope.Continuation continuation = getAndResetContinuation();
    if (null != continuation) {
      continuation.cancel();
    }
  }

  private AgentScope.Continuation getAndResetContinuation() {
    AgentScope.Continuation continuation = this.continuation;
    if (continuation != null) {
      if (CONTINUATION.compareAndSet(this, continuation, null)) {
        return continuation;
      }
    }
    return null;
  }

  public static SingleResultCallback<Object> wrapIfRequired(SingleResultCallback<Object> callback) {
    AgentScope.Continuation continuation = captureActiveSpan();
    if (continuation != noopContinuation()) {
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

package datadog.trace.instrumentation.netty4.promise;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.captureActiveSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.noopContinuation;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import io.netty.util.concurrent.GenericProgressiveFutureListener;
import io.netty.util.concurrent.ProgressiveFuture;

public final class ListenerWrapper {

  public static GenericFutureListener wrapIfNeeded(final GenericFutureListener listener) {
    if (listener == null || listener instanceof GenericWrapper) {
      return listener;
    }
    AgentScope.Continuation continuation = captureActiveSpan();
    if (continuation == noopContinuation()) {
      return listener;
    }
    if (listener instanceof GenericProgressiveFutureListener) {
      return new GenericProgressiveWrapper(
          (GenericProgressiveFutureListener<?>) listener, continuation);
    }
    return new GenericWrapper(listener, continuation);
  }

  private static class GenericWrapper<T extends Future<?>> implements GenericFutureListener<T> {

    private final GenericFutureListener<T> listener;
    final AgentScope.Continuation continuation;

    public GenericWrapper(
        final GenericFutureListener<T> listener, AgentScope.Continuation continuation) {
      this.listener = listener;
      this.continuation = continuation;
    }

    @Override
    public void operationComplete(T future) throws Exception {
      try (AgentScope scope = continuation.activate()) {
        listener.operationComplete(future);
      }
    }
  }

  private static class GenericProgressiveWrapper<S extends ProgressiveFuture<?>>
      extends GenericWrapper<S> implements GenericProgressiveFutureListener<S> {

    private final GenericProgressiveFutureListener<S> listener;

    public GenericProgressiveWrapper(
        GenericProgressiveFutureListener<S> listener, AgentScope.Continuation continuation) {
      super(listener, continuation);
      this.listener = listener;
    }

    @Override
    public void operationProgressed(S future, long progress, long total) throws Exception {
      // not yet complete, not ready to do final activation of continuation
      try (AgentScope scope = activateSpan(continuation.span())) {
        listener.operationProgressed(future, progress, total);
      }
    }
  }
}

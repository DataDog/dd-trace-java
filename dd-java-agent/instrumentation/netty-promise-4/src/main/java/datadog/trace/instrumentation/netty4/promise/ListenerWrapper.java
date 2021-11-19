package datadog.trace.instrumentation.netty4.promise;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeScope;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import io.netty.util.concurrent.GenericProgressiveFutureListener;
import io.netty.util.concurrent.ProgressiveFuture;

public final class ListenerWrapper {

  public static GenericFutureListener wrapIfNeeded(final GenericFutureListener listener) {
    AgentScope scope = activeScope();
    AgentSpan span = activeSpan();

    if (listener != null
        && span != null
        && scope != null
        && scope.isAsyncPropagating()
        && !(listener instanceof GenericWrapper)) {
      if (listener instanceof GenericProgressiveFutureListener) {
        return new GenericProgressiveWrapper(
            (GenericProgressiveFutureListener<?>) listener, span, scope.capture());
      }
      return new GenericWrapper(listener, scope.capture());
    }
    return listener;
  }

  private static class GenericWrapper<T extends Future<?>> implements GenericFutureListener<T> {

    private final GenericFutureListener<T> listener;
    private final AgentScope.Continuation continuation;

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
    private final AgentSpan span;

    public GenericProgressiveWrapper(
        GenericProgressiveFutureListener<S> listener,
        AgentSpan span,
        AgentScope.Continuation continuation) {
      super(listener, continuation);
      this.listener = listener;
      this.span = span;
    }

    @Override
    public void operationProgressed(S future, long progress, long total) throws Exception {
      try (AgentScope scope = activateSpan(span)) {
        listener.operationProgressed(future, progress, total);
      }
    }
  }
}

package datadog.trace.instrumentation.netty4.promise;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeScope;

import datadog.trace.context.TraceScope;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class GenericFutureListenerWrapper implements GenericFutureListener {

  private final GenericFutureListener listener;
  private final TraceScope.Continuation continuation;

  public GenericFutureListenerWrapper(
      final GenericFutureListener listener, TraceScope.Continuation continuation) {
    this.listener = listener;
    this.continuation = continuation;
  }

  @Override
  public void operationComplete(Future future) throws Exception {
    try (TraceScope scope = continuation.activate()) {
      listener.operationComplete(future);
    }
  }

  public static GenericFutureListener wrapIfNeeded(final GenericFutureListener listener) {
    TraceScope scope = activeScope();

    if (scope != null
        && scope.isAsyncPropagating()
        && !(listener instanceof GenericFutureListenerWrapper)) {
      return new GenericFutureListenerWrapper(listener, scope.capture());
    }
    return listener;
  }
}

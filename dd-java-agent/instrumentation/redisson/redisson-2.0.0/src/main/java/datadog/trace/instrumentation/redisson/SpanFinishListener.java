package datadog.trace.instrumentation.redisson;

import static datadog.trace.instrumentation.redisson.RedissonClientDecorator.DECORATE;

import datadog.context.ContextContinuation;
import datadog.context.ContextScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;

public class SpanFinishListener implements GenericFutureListener<Future<Object>> {
  private final ContextContinuation continuation;

  public SpanFinishListener(final ContextContinuation continuation) {
    this.continuation = continuation;
  }

  @Override
  public void operationComplete(Future<Object> future) throws Exception {
    try (final ContextScope scope = continuation.resume()) {
      if (!future.isSuccess()) {
        DECORATE.onError(scope, future.cause());
      }
      DECORATE.beforeFinish(scope);
      AgentSpan.fromContext(scope.context()).finish();
    }
  }
}

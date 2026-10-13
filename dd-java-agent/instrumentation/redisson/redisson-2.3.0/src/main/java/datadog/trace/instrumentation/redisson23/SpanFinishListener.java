package datadog.trace.instrumentation.redisson23;

import datadog.context.ContextContinuation;
import datadog.context.ContextScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.FutureListener;

public class SpanFinishListener implements FutureListener<Object> {
  private final ContextContinuation continuation;

  public SpanFinishListener(final ContextContinuation continuation) {
    this.continuation = continuation;
  }

  @Override
  public void operationComplete(Future<Object> future) throws Exception {
    try (final ContextScope scope = continuation.resume()) {
      if (!future.isSuccess()) {
        RedissonClientDecorator.DECORATE.onError(scope, future.cause());
      }
      RedissonClientDecorator.DECORATE.beforeFinish(scope);
      AgentSpan.fromContext(scope.context()).finish();
    }
  }
}

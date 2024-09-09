package datadog.trace.instrumentation.redisson23;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.FutureListener;

public class SpanFinishListener implements FutureListener<Object> {
  private final AgentScope.Continuation continuation;

  public SpanFinishListener(final AgentScope.Continuation continuation) {
    this.continuation = continuation;
  }

  @Override
  public void operationComplete(Future<Object> future) throws Exception {
    try (final AgentScope scope = continuation.activate()) {
      if (!future.isSuccess()) {
        RedissonClientDecorator.DECORATE.onError(scope, future.cause());
      }
      RedissonClientDecorator.DECORATE.beforeFinish(scope);
      scope.span().finish();
    }
  }
}

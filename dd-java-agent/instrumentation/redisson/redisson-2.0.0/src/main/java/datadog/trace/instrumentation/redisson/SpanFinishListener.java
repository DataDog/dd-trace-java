package datadog.trace.instrumentation.redisson;

import static datadog.trace.instrumentation.redisson.RedissonClientDecorator.DECORATE;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;

public class SpanFinishListener implements GenericFutureListener<Future<Object>> {
  private final AgentScope.Continuation continuation;

  public SpanFinishListener(final AgentScope.Continuation continuation) {
    this.continuation = continuation;
  }

  @Override
  public void operationComplete(Future<Object> future) throws Exception {
    try (final AgentScope scope = continuation.activate()) {
      if (!future.isSuccess()) {
        DECORATE.onError(scope, future.cause());
      }
      DECORATE.beforeFinish(scope);
      scope.span().finish();
    }
  }
}

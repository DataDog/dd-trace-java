package datadog.trace.instrumentation.redisson30;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import java.util.function.BiConsumer;

public class SpanFinishListener implements BiConsumer<Object, Throwable> {
  private final AgentScope.Continuation continuation;

  public SpanFinishListener(final AgentScope.Continuation continuation) {
    this.continuation = continuation;
  }

  @Override
  public void accept(Object o, Throwable throwable) {
    try (final AgentScope scope = continuation.activate()) {
      if (throwable != null) {
        RedissonClientDecorator.DECORATE.onError(scope, throwable);
      }
      RedissonClientDecorator.DECORATE.beforeFinish(scope);
      scope.span().finish();
    }
  }
}

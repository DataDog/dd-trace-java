package datadog.trace.instrumentation.redisson30;

import datadog.context.ContextContinuation;
import datadog.context.ContextScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.function.BiConsumer;

public class SpanFinishListener implements BiConsumer<Object, Throwable> {
  private final ContextContinuation continuation;

  public SpanFinishListener(final ContextContinuation continuation) {
    this.continuation = continuation;
  }

  @Override
  public void accept(Object o, Throwable throwable) {
    AgentSpan span = AgentSpan.fromContext(continuation.context());
    try (final ContextScope scope = continuation.resume()) {
      if (throwable != null) {
        RedissonClientDecorator.DECORATE.onError(scope, throwable);
      }
      RedissonClientDecorator.DECORATE.beforeFinish(scope);
      span.finish();
    }
  }
}

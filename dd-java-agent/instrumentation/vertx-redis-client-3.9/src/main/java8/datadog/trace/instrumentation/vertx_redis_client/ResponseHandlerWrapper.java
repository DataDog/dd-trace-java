package datadog.trace.instrumentation.vertx_redis_client;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.context.TraceScope;
import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.redis.client.Response;

public class ResponseHandlerWrapper implements Handler<AsyncResult<Response>> {
  private final Handler<AsyncResult<Response>> handler;
  private final AgentSpan clientSpan;
  private final TraceScope.Continuation parentContinuation;

  public ResponseHandlerWrapper(
      final Handler<AsyncResult<Response>> handler,
      final AgentSpan clientSpan,
      final TraceScope.Continuation parentContinuation) {
    this.handler = handler;
    this.clientSpan = clientSpan;
    this.parentContinuation = parentContinuation;
  }

  @Override
  public void handle(final AsyncResult<Response> event) {
    TraceScope scope = null;
    try {
      if (null != clientSpan) {
        clientSpan.finish();
      }
      if (null != parentContinuation) {
        scope = parentContinuation.activate();
      }
      handler.handle(event);
    } finally {
      if (null != scope) {
        scope.close();
      }
    }
  }
}

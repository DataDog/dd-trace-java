package datadog.trace.instrumentation.vertx_redis_client;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.redis.client.Response;

public class ResponseHandlerWrapper implements Handler<AsyncResult<Response>> {
  private final Handler<AsyncResult<Response>> handler;
  private final AgentSpan span;

  public ResponseHandlerWrapper(
      final Handler<AsyncResult<Response>> handler, final AgentSpan span) {
    this.handler = handler;
    this.span = span;
  }

  @Override
  public void handle(final AsyncResult<Response> event) {
    AgentScope scope = null;
    try {
      if (null != span) {
        scope = activateSpan(span);
      }
      handler.handle(event);
    } finally {
      if (null != scope) {
        scope.close();
      }
      span.finish();
    }
  }
}

package datadog.trace.instrumentation.vertx_redis_client;

import static datadog.trace.instrumentation.vertx_redis_client.VertxRedisClientDecorator.DECORATE;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.redis.client.Response;

public class ResponseHandler implements Handler<AsyncResult<Response>> {
  public final AgentSpan clientSpan;
  private final AgentScope.Continuation continuation;
  private final Promise<Response> promise;

  public ResponseHandler(
      final Promise<Response> promise,
      final AgentSpan clientSpan,
      final AgentScope.Continuation continuation) {
    this.clientSpan = clientSpan;
    this.continuation = continuation;
    this.promise = promise;
  }

  @Override
  public void handle(final AsyncResult<Response> event) {
    if (clientSpan != null) {
      AgentScope scope = null;
      try {
        // Close client scope and span
        if (!event.succeeded()) {
          DECORATE.onError(clientSpan, event.cause());
        }
        clientSpan.finish();
        // Activate parent continuation and trigger promise completion
        if (continuation != null) {
          scope = continuation.activate();
        }
        if (event.succeeded()) {
          promise.complete(event.result());
        } else {
          promise.fail(event.cause());
        }
      } finally {
        // Deactivate parent continuation
        if (scope != null) {
          scope.close();
        }
      }
    }
  }
}

package datadog.trace.instrumentation.vertx_redis_client_4;

import static datadog.trace.instrumentation.vertx_redis_client_4.VertxRedisClientDecorator.DECORATE;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.redis.client.Response;

public class ResponseHandler implements Handler<AsyncResult<Response>> {
  public final AgentScope clientScope;
  private final AgentScope.Continuation continuation;

  private Promise<Response> promise;

  public ResponseHandler(
      final Promise<Response> promise,
      final AgentScope clientScope,
      final AgentScope.Continuation continuation) {
    this.clientScope = clientScope;
    this.continuation = continuation;
    this.promise = promise;
  }

  @Override
  public void handle(final AsyncResult<Response> event) {
    if (clientScope != null) {
      AgentScope scope = null;
      try {
        // Close client scope and span
        if (!event.succeeded()) {
          DECORATE.onError(clientScope, event.cause());
        }
        clientScope.close();
        clientScope.span().finish();
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

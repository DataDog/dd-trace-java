package datadog.trace.instrumentation.vertx_redis_client;

import static datadog.trace.instrumentation.vertx_redis_client.VertxRedisClientDecorator.DECORATE;

import datadog.context.ContextContinuation;
import datadog.context.ContextScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.redis.client.Response;

public class ResponseHandler implements Handler<AsyncResult<Response>> {
  public final AgentSpan clientSpan;
  private final ContextContinuation continuation;
  private final Promise<Response> promise;
  private boolean handled = false;

  public ResponseHandler(
      final Promise<Response> promise,
      final AgentSpan clientSpan,
      final ContextContinuation continuation) {
    this.clientSpan = clientSpan;
    this.continuation = continuation;
    this.promise = promise;
  }

  @Override
  public void handle(final AsyncResult<Response> event) {
    if (!handled && clientSpan != null) {
      handled = true;
      ContextScope scope = null;
      try {
        // Close client scope and span
        if (!event.succeeded()) {
          DECORATE.onError(clientSpan, event.cause());
        }
        clientSpan.finish();
        // Activate parent continuation and trigger promise completion
        if (continuation != null) {
          scope = continuation.resume();
        }
        promise.handle(event);
      } finally {
        // Deactivate parent continuation
        if (scope != null) {
          scope.close();
        }
      }
    }
  }
}

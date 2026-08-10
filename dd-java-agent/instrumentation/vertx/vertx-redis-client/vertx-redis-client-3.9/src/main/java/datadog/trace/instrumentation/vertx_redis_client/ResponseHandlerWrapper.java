package datadog.trace.instrumentation.vertx_redis_client;

import datadog.context.ContextContinuation;
import datadog.context.ContextScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.redis.client.Response;

public class ResponseHandlerWrapper implements Handler<AsyncResult<Response>> {
  private final Handler<AsyncResult<Response>> handler;
  public final AgentSpan clientSpan;
  private final ContextContinuation parentContinuation;
  private boolean handled = false;

  public ResponseHandlerWrapper(
      final Handler<AsyncResult<Response>> handler,
      final AgentSpan clientSpan,
      final ContextContinuation parentContinuation) {
    this.handler = handler;
    this.clientSpan = clientSpan;
    this.parentContinuation = parentContinuation;
  }

  @Override
  public void handle(final AsyncResult<Response> event) {
    /*
    Same handler can be called twice
     - there is no indication whether a handler was added to an already completed Future
     - we check the Future state right after adding the handler and if it is completed
       we execute the handler; of course the Future might have completed with the handler
       already set so the handler state must be tracked here to prevent double execution
    */
    if (!handled) {
      handled = true;
      ContextScope scope = null;
      try {
        if (null != clientSpan) {
          clientSpan.finish();
        }
        if (null != parentContinuation) {
          scope = parentContinuation.resume();
        }
        handler.handle(event);
      } finally {
        if (null != scope) {
          scope.close();
        }
      }
    }
  }
}

package datadog.trace.instrumentation.vertx_redis_client_4;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.redis.client.Response;

public class ResponseHandlerWrapper implements Handler<AsyncResult<Response>> {
  public final AgentSpan clientSpan;
  private final AgentScope.Continuation parentContinuation;
  private boolean handled = false;

  public ResponseHandlerWrapper(
      final AgentSpan clientSpan, final AgentScope.Continuation parentContinuation) {
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
      AgentScope scope = null;
      try {
        if (clientSpan != null) {
          clientSpan.finish();
        }
        if (parentContinuation != null) {
          scope = parentContinuation.activate();
        }
      } finally {
        if (scope != null) {
          scope.close();
        }
        handled = true;
      }
    }
  }
}

package datadog.trace.instrumentation.vertx_sql_client_39;

import datadog.context.ContextContinuation;
import datadog.context.ContextScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.sqlclient.SqlResult;

public class QueryResultHandlerWrapper<T, R extends SqlResult<T>>
    implements Handler<AsyncResult<R>> {
  private final Handler<AsyncResult<R>> handler;
  private final AgentSpan clientSpan;
  private final ContextContinuation parentContinuation;

  public QueryResultHandlerWrapper(
      final Handler<AsyncResult<R>> handler,
      final AgentSpan clientSpan,
      final ContextContinuation parentContinuation) {
    this.handler = handler;
    this.clientSpan = clientSpan;
    this.parentContinuation = parentContinuation;
  }

  @Override
  public void handle(final AsyncResult<R> event) {
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

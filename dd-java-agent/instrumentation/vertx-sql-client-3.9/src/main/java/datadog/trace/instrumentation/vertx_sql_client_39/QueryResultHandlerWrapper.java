package datadog.trace.instrumentation.vertx_sql_client_39;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.sqlclient.SqlResult;

public class QueryResultHandlerWrapper<T, R extends SqlResult<T>>
    implements Handler<AsyncResult<R>> {
  private final Handler<AsyncResult<R>> handler;
  private final AgentSpan clientSpan;
  private final AgentScope.Continuation parentContinuation;

  public QueryResultHandlerWrapper(
      final Handler<AsyncResult<R>> handler,
      final AgentSpan clientSpan,
      final AgentScope.Continuation parentContinuation) {
    this.handler = handler;
    this.clientSpan = clientSpan;
    this.parentContinuation = parentContinuation;
  }

  @Override
  public void handle(final AsyncResult<R> event) {
    AgentScope scope = null;
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

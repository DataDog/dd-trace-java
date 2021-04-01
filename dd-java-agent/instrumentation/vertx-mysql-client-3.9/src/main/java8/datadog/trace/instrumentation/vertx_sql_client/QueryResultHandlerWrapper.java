package datadog.trace.instrumentation.vertx_sql_client;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.sqlclient.SqlResult;

public class QueryResultHandlerWrapper<T, R extends SqlResult<T>>
    implements Handler<AsyncResult<R>> {
  private final Handler<AsyncResult<R>> handler;
  private final AgentSpan span;

  public QueryResultHandlerWrapper(final Handler<AsyncResult<R>> handler, final AgentSpan span) {
    this.handler = handler;
    this.span = span;
  }

  @Override
  public void handle(final AsyncResult<R> event) {
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

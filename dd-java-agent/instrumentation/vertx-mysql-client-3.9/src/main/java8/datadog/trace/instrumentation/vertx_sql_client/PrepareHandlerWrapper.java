package datadog.trace.instrumentation.vertx_sql_client;

import datadog.trace.bootstrap.ContextStore;
import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.sqlclient.PreparedStatement;

public class PrepareHandlerWrapper implements Handler<AsyncResult<PreparedStatement>> {
  private final Handler<AsyncResult<PreparedStatement>> handler;
  private final ContextStore<PreparedStatement, QueryInfo> contextStore;
  private final QueryInfo queryInfo;

  public PrepareHandlerWrapper(
      Handler<AsyncResult<PreparedStatement>> handler,
      ContextStore<PreparedStatement, QueryInfo> contextStore,
      QueryInfo queryInfo) {
    this.handler = handler;
    this.contextStore = contextStore;
    this.queryInfo = queryInfo;
  }

  @Override
  public void handle(AsyncResult<PreparedStatement> event) {
    if (event.succeeded()) {
      contextStore.put(event.result(), queryInfo);
    }
    handler.handle(event);
  }
}

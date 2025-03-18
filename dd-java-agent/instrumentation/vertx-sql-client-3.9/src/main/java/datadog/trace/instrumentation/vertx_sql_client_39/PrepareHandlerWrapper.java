package datadog.trace.instrumentation.vertx_sql_client_39;

import datadog.trace.api.Pair;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.jdbc.DBInfo;
import datadog.trace.bootstrap.instrumentation.jdbc.DBQueryInfo;
import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.sqlclient.PreparedStatement;

public class PrepareHandlerWrapper implements Handler<AsyncResult<PreparedStatement>> {
  private final Handler<AsyncResult<PreparedStatement>> handler;
  private final ContextStore<PreparedStatement, Pair> contextStore;
  private final Pair<DBInfo, DBQueryInfo> queryInfo;

  public PrepareHandlerWrapper(
      Handler<AsyncResult<PreparedStatement>> handler,
      ContextStore<PreparedStatement, Pair> contextStore,
      Pair<DBInfo, DBQueryInfo> queryInfo) {
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

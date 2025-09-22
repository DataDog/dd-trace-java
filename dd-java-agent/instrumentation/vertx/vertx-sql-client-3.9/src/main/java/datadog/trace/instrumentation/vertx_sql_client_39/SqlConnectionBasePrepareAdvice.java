package datadog.trace.instrumentation.vertx_sql_client_39;

import datadog.trace.api.Pair;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.jdbc.DBInfo;
import datadog.trace.bootstrap.instrumentation.jdbc.DBQueryInfo;
import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.sqlclient.PreparedStatement;
import io.vertx.sqlclient.SqlClient;
import net.bytebuddy.asm.Advice;

public class SqlConnectionBasePrepareAdvice {
  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static void beforePrepare(
      @Advice.This final SqlClient zis,
      @Advice.Argument(0) final String sql,
      @Advice.Argument(value = 1, readOnly = false)
          Handler<AsyncResult<PreparedStatement>> handler) {
    Pair<DBInfo, DBQueryInfo> info =
        Pair.of(
            InstrumentationContext.get(SqlClient.class, DBInfo.class).get(zis),
            DBQueryInfo.ofStatement(sql));

    handler =
        new PrepareHandlerWrapper(
            handler, InstrumentationContext.get(PreparedStatement.class, Pair.class), info);
  }
}

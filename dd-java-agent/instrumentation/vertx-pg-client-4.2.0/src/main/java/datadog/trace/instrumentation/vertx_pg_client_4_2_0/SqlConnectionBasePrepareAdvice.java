package datadog.trace.instrumentation.vertx_pg_client_4_2_0;

import datadog.trace.api.Pair;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.jdbc.DBInfo;
import datadog.trace.bootstrap.instrumentation.jdbc.DBQueryInfo;
import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.pgclient.PgConnection;
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

  // Limit ourselves to 4.x by checking for the ping() method that was added in 4.x
  private static void muzzleCheck(PgConnection connection) {
    connection.query("SELECT 1");
  }
}

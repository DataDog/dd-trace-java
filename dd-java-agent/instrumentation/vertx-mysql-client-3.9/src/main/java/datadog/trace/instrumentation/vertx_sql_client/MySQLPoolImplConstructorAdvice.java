package datadog.trace.instrumentation.vertx_sql_client;

import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.jdbc.DBInfo;
import io.vertx.mysqlclient.MySQLConnectOptions;
import io.vertx.sqlclient.Query;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.SqlConnection;
import net.bytebuddy.asm.Advice;

public class MySQLPoolImplConstructorAdvice {
  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void afterConstructor(
      @Advice.This final SqlClient zis, @Advice.Argument(2) final MySQLConnectOptions options) {
    DBInfo.Builder builder = DBInfo.DEFAULT.toBuilder();
    DBInfo info =
        builder
            .host(options.getHost())
            .port(options.getPort())
            .db(options.getDatabase())
            .user(options.getUser())
            .type("mysql")
            .build();
    InstrumentationContext.get(SqlClient.class, DBInfo.class).put(zis, info);
  }

  // Limit ourselves to 3.9.x by checking for the close() method that was removed in 4.x
  // and the Query interface that was added in 3.9.x
  private static void muzzleCheck(SqlConnection connection, Query query) {
    connection.close();
    query.execute(null);
  }
}

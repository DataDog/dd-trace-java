package datadog.trace.instrumentation.vertx_sql_client_4;

import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.jdbc.DBInfo;
import io.vertx.mysqlclient.MySQLConnectOptions;
import io.vertx.mysqlclient.MySQLConnection;
import io.vertx.sqlclient.SqlClient;
import net.bytebuddy.asm.Advice;

public class MySQLPoolImplAdvice {

  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void afterCreate(
      @Advice.Return final SqlClient zis, @Advice.Argument(2) MySQLConnectOptions options) {
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

  // Limit ourselves to 4.x by checking for the ping() method that was added in 4.x
  private static void muzzleCheck(MySQLConnection connection) {
    connection.ping();
  }
}

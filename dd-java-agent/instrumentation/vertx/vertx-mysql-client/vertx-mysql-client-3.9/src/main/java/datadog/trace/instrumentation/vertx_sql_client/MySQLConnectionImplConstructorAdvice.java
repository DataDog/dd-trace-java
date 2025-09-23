package datadog.trace.instrumentation.vertx_sql_client;

import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.jdbc.DBInfo;
import io.vertx.mysqlclient.impl.MySQLConnectionFactory;
import io.vertx.sqlclient.Query;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.SqlConnection;
import net.bytebuddy.asm.Advice;

public class MySQLConnectionImplConstructorAdvice {
  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void afterConstructor(
      @Advice.This final SqlClient zis, @Advice.Argument(0) final MySQLConnectionFactory factory) {
    InstrumentationContext.get(SqlClient.class, DBInfo.class)
        .put(
            zis,
            InstrumentationContext.get(MySQLConnectionFactory.class, DBInfo.class).get(factory));
  }

  // Limit ourselves to 3.9.x by checking for the close() method that was removed in 4.x
  // and the Query interface that was added in 3.9.x
  private static void muzzleCheck(SqlConnection connection, Query query) {
    connection.close();
    query.execute(null);
  }
}

package datadog.trace.instrumentation.vertx_sql_client_4_4_2;

import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.jdbc.DBInfo;
import io.vertx.mysqlclient.MySQLConnection;
import io.vertx.mysqlclient.impl.MySQLConnectionFactory;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.spi.ConnectionFactory;
import net.bytebuddy.asm.Advice;

public class SqlConnectionBaseConstructorAdvice {

  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void afterConstructor(
      @Advice.This final SqlClient zis,
      @Advice.Argument(1) final ConnectionFactory connectionFactory) {

    if (connectionFactory instanceof MySQLConnectionFactory) {
      InstrumentationContext.get(SqlClient.class, DBInfo.class)
          .put(
              zis,
              InstrumentationContext.get(MySQLConnectionFactory.class, DBInfo.class)
                  .get((MySQLConnectionFactory) connectionFactory));
    }
  }

  // Limit ourselves to 4.x by checking for the ping() method that was added in 4.x
  private static void muzzleCheck(MySQLConnection connection) {
    connection.ping();
  }
}

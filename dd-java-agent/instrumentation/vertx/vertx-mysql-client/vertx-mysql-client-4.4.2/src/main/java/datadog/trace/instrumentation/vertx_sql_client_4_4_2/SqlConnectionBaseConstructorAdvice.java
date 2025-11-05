package datadog.trace.instrumentation.vertx_sql_client_4_4_2;

import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.jdbc.DBInfo;
import io.vertx.core.Future;
import io.vertx.mysqlclient.MySQLConnectOptions;
import io.vertx.mysqlclient.MySQLConnection;
import io.vertx.mysqlclient.impl.MySQLConnectionFactory;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.impl.SingletonSupplier;
import io.vertx.sqlclient.spi.ConnectionFactory;
import java.util.function.Supplier;
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

  // Limit ourselves to 4.4.2+ by using SingletonSupplier which was added in 4.4.2
  private static void muzzleCheck(MySQLConnection connection) {
    connection.ping();
    Supplier<Future<MySQLConnectOptions>> supplier =
        SingletonSupplier.wrap(new MySQLConnectOptions());
  }
}

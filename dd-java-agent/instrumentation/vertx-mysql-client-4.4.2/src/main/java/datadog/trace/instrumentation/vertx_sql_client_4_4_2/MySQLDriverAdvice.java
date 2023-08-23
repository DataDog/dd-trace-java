package datadog.trace.instrumentation.vertx_sql_client_4_4_2;

import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.jdbc.DBInfo;
import io.vertx.core.Future;
import io.vertx.mysqlclient.MySQLConnectOptions;
import io.vertx.mysqlclient.MySQLConnection;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.SqlConnectOptions;
import io.vertx.sqlclient.impl.SingletonSupplier;
import java.util.function.Supplier;
import net.bytebuddy.asm.Advice;

public class MySQLDriverAdvice {

  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void afterNewPoolImpl(
      @Advice.Return final SqlClient zis,
      @Advice.Argument(1) final Supplier<? extends Future<? extends SqlConnectOptions>> databases) {

    if (databases instanceof SingletonSupplier) {
      SqlConnectOptions options = (SqlConnectOptions) ((SingletonSupplier) databases).unwrap();
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
  }

  // Limit ourselves to 4.4.2+ by using SingletonSupplier which was added in 4.4.2
  private static void muzzleCheck(MySQLConnection connection) {
    connection.ping();
    Supplier<Future<MySQLConnectOptions>> supplier =
        SingletonSupplier.wrap(new MySQLConnectOptions());
  }
}

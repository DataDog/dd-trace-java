package datadog.trace.instrumentation.vertx_sql_client_4_4_2;

import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.jdbc.DBInfo;
import io.vertx.core.Future;
import io.vertx.mysqlclient.MySQLConnection;
import io.vertx.mysqlclient.impl.MySQLConnectionFactory;
import io.vertx.sqlclient.SqlConnectOptions;
import io.vertx.sqlclient.impl.SingletonSupplier;
import java.util.function.Supplier;
import net.bytebuddy.asm.Advice;

public class MySQLConnectionFactoryConstructorAdvice {
  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void afterConstructor(
      @Advice.This final MySQLConnectionFactory factory,
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
      InstrumentationContext.get(MySQLConnectionFactory.class, DBInfo.class).put(factory, info);
    }
  }

  // Limit ourselves to 4.x by checking for the ping() method that was added in 4.x
  private static void muzzleCheck(MySQLConnection connection) {
    connection.ping();
  }
}

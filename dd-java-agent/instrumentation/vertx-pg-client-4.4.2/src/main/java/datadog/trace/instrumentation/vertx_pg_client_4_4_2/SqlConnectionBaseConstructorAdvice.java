package datadog.trace.instrumentation.vertx_pg_client_4_4_2;

import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.jdbc.DBInfo;
import io.vertx.core.Future;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgConnection;
import io.vertx.pgclient.impl.PgConnectionFactory;
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

    if (connectionFactory instanceof PgConnectionFactory) {
      InstrumentationContext.get(SqlClient.class, DBInfo.class)
          .put(
              zis,
              InstrumentationContext.get(PgConnectionFactory.class, DBInfo.class)
                  .get((PgConnectionFactory) connectionFactory));
    }
  }

  // Limit ourselves to 4.4.2+ by using SingletonSupplier which was added in 4.4.2
  private static void muzzleCheck(PgConnection connection) {
    Supplier<Future<PgConnectOptions>> supplier = SingletonSupplier.wrap(new PgConnectOptions());
  }
}

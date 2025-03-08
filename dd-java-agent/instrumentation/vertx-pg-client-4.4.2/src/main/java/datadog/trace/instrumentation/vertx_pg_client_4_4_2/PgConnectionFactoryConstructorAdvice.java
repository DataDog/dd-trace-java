package datadog.trace.instrumentation.vertx_pg_client_4_4_2;

import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.jdbc.DBInfo;
import io.vertx.core.Future;
import io.vertx.pgclient.impl.PgConnectionFactory;
import io.vertx.sqlclient.SqlConnectOptions;
import io.vertx.sqlclient.impl.SingletonSupplier;
import java.util.function.Supplier;
import net.bytebuddy.asm.Advice;

public class PgConnectionFactoryConstructorAdvice {
  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void afterConstructor(
      @Advice.This final PgConnectionFactory factory,
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
              .type("postgresql")
              .build();
      InstrumentationContext.get(PgConnectionFactory.class, DBInfo.class).put(factory, info);
    }
  }
}

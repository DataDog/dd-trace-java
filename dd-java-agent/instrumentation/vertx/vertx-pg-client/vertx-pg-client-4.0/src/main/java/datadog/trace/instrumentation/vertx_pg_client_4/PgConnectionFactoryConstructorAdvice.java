package datadog.trace.instrumentation.vertx_pg_client_4;

import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.jdbc.DBInfo;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.impl.PgConnectionFactory;
import net.bytebuddy.asm.Advice;

public class PgConnectionFactoryConstructorAdvice {
  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void afterConstructor(
      @Advice.This final PgConnectionFactory zis,
      @Advice.Argument(1) final PgConnectOptions options) {
    DBInfo.Builder builder = DBInfo.DEFAULT.toBuilder();
    DBInfo info =
        builder
            .host(options.getHost())
            .port(options.getPort())
            .db(options.getDatabase())
            .user(options.getUser())
            .type("postgresql")
            .build();
    InstrumentationContext.get(PgConnectionFactory.class, DBInfo.class).put(zis, info);
  }
}

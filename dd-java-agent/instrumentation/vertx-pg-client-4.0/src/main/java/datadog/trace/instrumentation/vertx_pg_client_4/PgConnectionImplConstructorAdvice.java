package datadog.trace.instrumentation.vertx_pg_client_4;

import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.jdbc.DBInfo;
import io.vertx.pgclient.impl.PgConnectionFactory;
import io.vertx.sqlclient.SqlClient;
import net.bytebuddy.asm.Advice;

public class PgConnectionImplConstructorAdvice {
  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void afterConstructor(
      @Advice.This final SqlClient zis, @Advice.Argument(0) final PgConnectionFactory factory) {
    InstrumentationContext.get(SqlClient.class, DBInfo.class)
        .put(zis, InstrumentationContext.get(PgConnectionFactory.class, DBInfo.class).get(factory));
  }
}

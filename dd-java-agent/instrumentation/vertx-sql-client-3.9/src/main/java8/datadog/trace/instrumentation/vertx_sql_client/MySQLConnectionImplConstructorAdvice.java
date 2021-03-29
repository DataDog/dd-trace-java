package datadog.trace.instrumentation.vertx_sql_client;

import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.jdbc.DBInfo;
import io.vertx.mysqlclient.impl.MySQLConnectionFactory;
import io.vertx.sqlclient.SqlClient;
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
}

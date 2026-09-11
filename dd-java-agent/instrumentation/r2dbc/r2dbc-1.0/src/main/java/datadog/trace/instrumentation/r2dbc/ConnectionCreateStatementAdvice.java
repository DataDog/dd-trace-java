package datadog.trace.instrumentation.r2dbc;

import datadog.trace.bootstrap.InstrumentationContext;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.Statement;
import net.bytebuddy.asm.Advice;

public class ConnectionCreateStatementAdvice {

  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void afterCreateStatement(
      @Advice.This Connection connection,
      @Advice.Argument(0) String sql,
      @Advice.Return Statement statement) {
    if (statement != null) {
      R2dbcConnectionInfo connInfo =
          InstrumentationContext.get(Connection.class, R2dbcConnectionInfo.class).get(connection);
      R2dbcStatementInfo stmtInfo = new R2dbcStatementInfo(sql, connInfo);
      InstrumentationContext.get(Statement.class, R2dbcStatementInfo.class)
          .put(statement, stmtInfo);
    }
  }
}

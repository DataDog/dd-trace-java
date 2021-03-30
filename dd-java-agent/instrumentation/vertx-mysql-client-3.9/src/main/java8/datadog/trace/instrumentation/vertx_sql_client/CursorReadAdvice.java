package datadog.trace.instrumentation.vertx_sql_client;

import static datadog.trace.instrumentation.vertx_sql_client.VertxSqlClientDecorator.DECORATE;

import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.mysqlclient.MySQLConnection;
import io.vertx.sqlclient.PreparedStatement;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

public class CursorReadAdvice {
  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static void beforeRead(
      @Advice.Argument(value = 1, readOnly = false) Handler<AsyncResult<RowSet<Row>>> handler,
      @Advice.FieldValue(value = "ps", typing = Assigner.Typing.DYNAMIC)
          final PreparedStatement ps) {
    if (handler instanceof QueryResultHandlerWrapper) {
      return;
    }
    final AgentSpan span =
        DECORATE.startAndDecorateSpanForStatement(
            ps, InstrumentationContext.get(PreparedStatement.class, QueryInfo.class), true);
    if (null != span) {
      handler = new QueryResultHandlerWrapper<>(handler, span);
    }
  }

  // Limit ourselves to 3.9.x and MySQL by checking for this method that was removed in 4.x
  private static void muzzleCheck(MySQLConnection connection) {
    connection.close();
  }
}

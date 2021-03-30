package datadog.trace.instrumentation.vertx_sql_client;

import static datadog.trace.instrumentation.vertx_sql_client.VertxSqlClientDecorator.DECORATE;

import datadog.trace.api.Pair;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.mysqlclient.MySQLConnection;
import io.vertx.sqlclient.Query;
import io.vertx.sqlclient.SqlResult;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

public class QueryAdvice {
  public static class Copy {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void afterCopy(
        @Advice.This final Query<?> zis, @Advice.Return final Query<?> ret) {
      ContextStore<Query, Pair> contextStore = InstrumentationContext.get(Query.class, Pair.class);
      contextStore.put(ret, contextStore.get(zis));
    }

    // Limit ourselves to 3.9.x and MySQL by checking for this method that was removed in 4.x
    private static void muzzleCheck(MySQLConnection connection) {
      connection.close();
    }
  }

  public static class Execute {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static <T, R extends SqlResult<T>> void beforeExecute(
        @Advice.This final Query<?> zis,
        @Advice.Argument(
                value = 0,
                readOnly = false,
                optional = true,
                typing = Assigner.Typing.DYNAMIC)
            Object maybeHandler,
        @Advice.Argument(value = 1, readOnly = false, optional = true)
            Handler<AsyncResult<R>> handler) {
      final boolean prepared = !(maybeHandler instanceof Handler);
      final AgentSpan span =
          DECORATE.startAndDecorateSpanForStatement(
              zis, InstrumentationContext.get(Query.class, Pair.class), prepared);
      if (null != span) {
        if (prepared) {
          handler = new QueryResultHandlerWrapper<>(handler, span);
        } else {
          maybeHandler =
              new QueryResultHandlerWrapper<>((Handler<AsyncResult<R>>) maybeHandler, span);
        }
      }
    }

    // Limit ourselves to 3.9.x and MySQL by checking for this method that was removed in 4.x
    private static void muzzleCheck(MySQLConnection connection) {
      connection.close();
    }
  }
}

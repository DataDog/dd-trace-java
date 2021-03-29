package datadog.trace.instrumentation.vertx_sql_client;

import static datadog.trace.instrumentation.vertx_sql_client.VertxSqlClientDecorator.DECORATE;

import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.sqlclient.Query;
import io.vertx.sqlclient.SqlResult;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

public class QueryAdvice {
  public static class Copy {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void afterCopy(
        @Advice.This final Query<?> zis, @Advice.Return final Query<?> ret) {
      ContextStore<Query, QueryInfo> contextStore =
          InstrumentationContext.get(Query.class, QueryInfo.class);
      contextStore.put(ret, contextStore.get(zis));
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
              zis, InstrumentationContext.get(Query.class, QueryInfo.class), prepared);
      if (null != span) {
        if (prepared) {
          handler = new QueryResultHandlerWrapper<>(handler, span);
        } else {
          maybeHandler =
              new QueryResultHandlerWrapper<>((Handler<AsyncResult<R>>) maybeHandler, span);
        }
      }
    }
  }
}

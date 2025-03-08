package datadog.trace.instrumentation.vertx_sql_client_39;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.captureSpan;
import static datadog.trace.instrumentation.vertx_sql_client_39.VertxSqlClientDecorator.DECORATE;

import datadog.trace.api.Pair;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
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
      ContextStore<Query, Pair> contextStore = InstrumentationContext.get(Query.class, Pair.class);
      contextStore.put(ret, contextStore.get(zis));
    }
  }

  public static class Execute {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static <T, R extends SqlResult<T>> AgentScope beforeExecute(
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

      final AgentSpan parentSpan = activeSpan();
      final AgentScope.Continuation parentContinuation =
          null == parentSpan ? null : captureSpan(parentSpan);
      final AgentSpan clientSpan =
          DECORATE.startAndDecorateSpanForStatement(
              zis, InstrumentationContext.get(Query.class, Pair.class), prepared);
      if (null == clientSpan) {
        return null;
      }
      if (prepared) {
        handler = new QueryResultHandlerWrapper<>(handler, clientSpan, parentContinuation);
      } else {
        maybeHandler =
            new QueryResultHandlerWrapper<>(
                (Handler<AsyncResult<R>>) maybeHandler, clientSpan, parentContinuation);
      }
      return activateSpan(clientSpan);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void afterExecute(
        @Advice.Thrown final Throwable throwable, @Advice.Enter final AgentScope clientScope) {
      if (null != clientScope) {
        clientScope.close();
      }
    }
  }
}

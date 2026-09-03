package datadog.trace.instrumentation.r2dbc;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.r2dbc.R2dbcDecorator.DATABASE_QUERY;
import static datadog.trace.instrumentation.r2dbc.R2dbcDecorator.DECORATE;

import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.r2dbc.spi.Result;
import io.r2dbc.spi.Statement;
import net.bytebuddy.asm.Advice;
import org.reactivestreams.Publisher;

public class StatementExecuteAdvice {

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static AgentScope onEnter(@Advice.This Statement statement) {
    int callDepth = CallDepthThreadLocalMap.incrementCallDepth(Statement.class);
    if (callDepth > 0) {
      return null;
    }

    R2dbcStatementInfo stmtInfo =
        InstrumentationContext.get(Statement.class, R2dbcStatementInfo.class).get(statement);

    String sql = stmtInfo != null ? stmtInfo.getSql() : null;
    R2dbcConnectionInfo connInfo = stmtInfo != null ? stmtInfo.getConnectionInfo() : null;

    AgentSpan span = startSpan("r2dbc-spi", DATABASE_QUERY);
    DECORATE.afterStart(span);
    DECORATE.onDatabase(span, connInfo);
    DECORATE.onStatement(span, sql);

    return activateSpan(span);
  }

  @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
  public static void onExit(
      @Advice.Enter AgentScope scope,
      @Advice.Return(readOnly = false) Publisher<? extends Result> publisher,
      @Advice.Thrown Throwable throwable) {
    CallDepthThreadLocalMap.decrementCallDepth(Statement.class);
    if (scope == null) {
      return;
    }

    AgentSpan span = scope.span();
    scope.close();

    if (throwable != null) {
      DECORATE.onError(span, throwable);
      DECORATE.beforeFinish(span);
      span.finish();
    } else if (publisher != null) {
      publisher = new TracingPublisher(publisher, span);
    } else {
      DECORATE.beforeFinish(span);
      span.finish();
    }
  }
}

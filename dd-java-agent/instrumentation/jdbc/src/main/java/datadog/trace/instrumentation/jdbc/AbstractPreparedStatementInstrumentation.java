package datadog.trace.instrumentation.jdbc;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.nameStartsWith;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.DBM_TRACE_INJECTED;
import static datadog.trace.instrumentation.jdbc.JDBCDecorator.DATABASE_QUERY;
import static datadog.trace.instrumentation.jdbc.JDBCDecorator.DBM_TRACE_PREPARED_STATEMENTS;
import static datadog.trace.instrumentation.jdbc.JDBCDecorator.DECORATE;
import static datadog.trace.instrumentation.jdbc.JDBCDecorator.INJECT_COMMENT;
import static datadog.trace.instrumentation.jdbc.JDBCDecorator.logMissingQueryInfo;
import static datadog.trace.instrumentation.jdbc.JDBCDecorator.logSQLException;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.jdbc.DBInfo;
import datadog.trace.bootstrap.instrumentation.jdbc.DBQueryInfo;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.asm.Advice;

public abstract class AbstractPreparedStatementInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForBootstrap, Instrumenter.HasMethodAdvice {

  public AbstractPreparedStatementInstrumentation(
      String instrumentationName, String... additionalNames) {
    super(instrumentationName, additionalNames);
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      "datadog.trace.core.propagation.W3CTraceParent", packageName + ".JDBCDecorator",
    };
  }

  @Override
  public Map<String, String> contextStore() {
    Map<String, String> contextStore = new HashMap<>(4);
    contextStore.put("java.sql.Statement", DBQueryInfo.class.getName());
    contextStore.put("java.sql.Connection", DBInfo.class.getName());
    return contextStore;
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        nameStartsWith("execute").and(takesArguments(0)).and(isPublic()),
        AbstractPreparedStatementInstrumentation.class.getName() + "$PreparedStatementAdvice");
  }

  public static class PreparedStatementAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope onEnter(@Advice.This final Statement statement) {
      int depth = CallDepthThreadLocalMap.incrementCallDepth(Statement.class);
      if (depth > 0) {
        return null;
      }
      try {
        final Connection connection = statement.getConnection();
        final DBQueryInfo queryInfo =
            InstrumentationContext.get(Statement.class, DBQueryInfo.class).get(statement);
        if (null == queryInfo) {
          logMissingQueryInfo(statement);
          return null;
        }
        final AgentSpan span;
        final DBInfo dbInfo =
            JDBCDecorator.parseDBInfo(
                connection, InstrumentationContext.get(Connection.class, DBInfo.class));
        final boolean injectTraceContext = DECORATE.shouldInjectTraceContext(dbInfo);

        if (INJECT_COMMENT && injectTraceContext) {
          if (DECORATE.isSqlServer(dbInfo)) {
            // The span ID is pre-determined so that we can reference it when setting the context
            final long spanID = DECORATE.setContextInfo(connection, dbInfo);
            // we then force that pre-determined span ID for the span covering the actual query
            span = AgentTracer.get().singleSpanBuilder(DATABASE_QUERY).withSpanId(spanID).start();
            span.setTag(DBM_TRACE_INJECTED, true);
          } else if (DECORATE.isPostgres(dbInfo) && DBM_TRACE_PREPARED_STATEMENTS) {
            span = startSpan(DATABASE_QUERY);
            DECORATE.setApplicationName(span, connection);
          } else if (DECORATE.isOracle(dbInfo)) {
            span = startSpan(DATABASE_QUERY);
            DECORATE.setAction(span, connection);
          } else {
            span = startSpan(DATABASE_QUERY);
          }
        } else {
          span = startSpan(DATABASE_QUERY);
        }
        DECORATE.afterStart(span);
        DECORATE.onConnection(span, dbInfo);
        DECORATE.onPreparedStatement(span, queryInfo);
        DECORATE.withBaseHash(span);

        return activateSpan(span);
      } catch (SQLException e) {
        logSQLException(e);
        // if we can't get the connection for any reason
        return null;
      }
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(
        @Advice.Enter final AgentScope scope, @Advice.Thrown final Throwable throwable) {
      CallDepthThreadLocalMap.decrementCallDepth(Statement.class);
      if (scope == null) {
        return;
      }
      DECORATE.onError(scope.span(), throwable);
      DECORATE.beforeFinish(scope.span());
      scope.close();
      scope.span().finish();
    }
  }
}

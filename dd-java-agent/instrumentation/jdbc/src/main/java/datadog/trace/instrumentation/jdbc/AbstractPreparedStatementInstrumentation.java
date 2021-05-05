package datadog.trace.instrumentation.jdbc;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.nameStartsWith;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.jdbc.JDBCDecorator.DATABASE_QUERY;
import static datadog.trace.instrumentation.jdbc.JDBCDecorator.DECORATE;
import static datadog.trace.instrumentation.jdbc.JDBCDecorator.logMissingQueryInfo;
import static datadog.trace.instrumentation.jdbc.JDBCDecorator.logSQLException;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.jdbc.DBInfo;
import datadog.trace.bootstrap.instrumentation.jdbc.DBQueryInfo;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.asm.Advice;

public abstract class AbstractPreparedStatementInstrumentation extends Instrumenter.Tracing {

  public AbstractPreparedStatementInstrumentation(
      String instrumentationName, String... additionalNames) {
    super(instrumentationName, additionalNames);
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".JDBCDecorator",
    };
  }

  @Override
  public Map<String, String> contextStore() {
    Map<String, String> contextStore = new HashMap<>(4);
    contextStore.put("java.sql.PreparedStatement", DBQueryInfo.class.getName());
    contextStore.put("java.sql.Statement", Boolean.class.getName());
    contextStore.put("java.sql.Connection", DBInfo.class.getName());
    return contextStore;
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        nameStartsWith("execute").and(takesArguments(0)).and(isPublic()),
        AbstractPreparedStatementInstrumentation.class.getName() + "$PreparedStatementAdvice");
  }

  public static class PreparedStatementAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope onEnter(@Advice.This final PreparedStatement statement) {
      ContextStore<Statement, Boolean> interceptionTracker =
          InstrumentationContext.get(Statement.class, Boolean.class);
      if (Boolean.TRUE.equals(interceptionTracker.get(statement))) {
        return null;
      }
      interceptionTracker.put(statement, Boolean.TRUE);
      try {
        Connection connection = statement.getConnection();
        DBQueryInfo queryInfo =
            InstrumentationContext.get(PreparedStatement.class, DBQueryInfo.class).get(statement);
        if (null == queryInfo) {
          logMissingQueryInfo(statement);
          return null;
        }

        final AgentSpan span = startSpan(DATABASE_QUERY);
        DECORATE.afterStart(span);
        DECORATE.onConnection(
            span, connection, InstrumentationContext.get(Connection.class, DBInfo.class));
        DECORATE.onPreparedStatement(span, queryInfo);
        return activateSpan(span);
      } catch (SQLException e) {
        logSQLException(e);
        // if we can't get the connection for any reason
        return null;
      }
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(
        @Advice.This PreparedStatement statement,
        @Advice.Enter final AgentScope scope,
        @Advice.Thrown final Throwable throwable) {
      if (scope == null) {
        return;
      }
      DECORATE.onError(scope.span(), throwable);
      DECORATE.beforeFinish(scope.span());
      scope.close();
      scope.span().finish();
      InstrumentationContext.get(Statement.class, Boolean.class).put(statement, null);
    }
  }
}

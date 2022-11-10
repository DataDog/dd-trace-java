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
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
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

public abstract class AbstractPreparedStatementInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForBootstrap {

  public AbstractPreparedStatementInstrumentation(
      String instrumentationName, String... additionalNames) {
    super(instrumentationName, additionalNames);
  }

  @Override
  public String[] helperClassNames() {
    return new String[]{
        packageName + ".JDBCDecorator",
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
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        nameStartsWith("execute").and(takesArguments(0)).and(isPublic()),
        AbstractPreparedStatementInstrumentation.class.getName() + "$PreparedStatementAdvice");
    transformation.applyAdvice(
        nameStartsWith("set").and(takesArguments(2)).and(isPublic()),
        AbstractPreparedStatementInstrumentation.class.getName() + "$SetStringAdvice");
  }

  public static class SetStringAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void StartSetString(
        //   @Advice.Argument(0) final int index, @Advice.Argument(1) final String arg,
        @Advice.AllArguments final Object[] args,
        @Advice.This final PreparedStatement statement) {
      int index = 0;
      String arg = "";
      if (args.length != 2) {
        return;
      }

      if (args[0] instanceof Integer) {
        index = (Integer) args[0];
      }
      arg = (args[1]).toString();


      try {
        ContextStore<Statement, DBQueryInfo> contextStore = InstrumentationContext.get(Statement.class, DBQueryInfo.class);
        if (contextStore == null) {
          return;
        }

        DBQueryInfo queryInfo = contextStore.get(statement);
        if (null == queryInfo) {
          logMissingQueryInfo(statement);
          return;
        }
        queryInfo.setVal(index, arg);
        contextStore.put(statement, queryInfo);
      } catch (SQLException e) {
        return;
      }
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(
        @Advice.Thrown final Throwable throwable) {
    }
  }

  public static class PreparedStatementAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope onEnter(@Advice.This final Statement statement) {

      try {
        Connection connection = statement.getConnection();
        DBQueryInfo queryInfo =
            InstrumentationContext.get(Statement.class, DBQueryInfo.class).get(statement);
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
        @Advice.Enter final AgentScope scope, @Advice.Thrown final Throwable throwable) {
      if (scope == null) {
        return;
      }
      DECORATE.onError(scope.span(), throwable);
      DECORATE.beforeFinish(scope.span());
      scope.close();
      scope.span().finish();
      CallDepthThreadLocalMap.reset(Statement.class);
    }
  }
}

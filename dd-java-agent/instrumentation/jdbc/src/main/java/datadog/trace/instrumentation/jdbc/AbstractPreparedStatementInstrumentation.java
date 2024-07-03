package datadog.trace.instrumentation.jdbc;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.nameStartsWith;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.jdbc.JDBCDecorator.DATABASE_QUERY;
import static datadog.trace.instrumentation.jdbc.JDBCDecorator.DECORATE;
import static datadog.trace.instrumentation.jdbc.JDBCDecorator.INJECT_COMMENT;
import static datadog.trace.instrumentation.jdbc.JDBCDecorator.logMissingQueryInfo;
import static datadog.trace.instrumentation.jdbc.JDBCDecorator.logSQLException;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.DDSpanId;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.jdbc.DBInfo;
import datadog.trace.bootstrap.instrumentation.jdbc.DBQueryInfo;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.asm.Advice;

public abstract class AbstractPreparedStatementInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForBootstrap {

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
        Connection connection = statement.getConnection();
        DBQueryInfo queryInfo =
            InstrumentationContext.get(Statement.class, DBQueryInfo.class).get(statement);
        if (null == queryInfo) {
          logMissingQueryInfo(statement);
          return null;
        }

        final AgentSpan span = startSpan(DATABASE_QUERY);
        DECORATE.afterStart(span);
        DBInfo dbInfo =
            JDBCDecorator.parseDBInfo(
                connection, InstrumentationContext.get(Connection.class, DBInfo.class));
        DECORATE.onConnection(span, dbInfo);
        DECORATE.onPreparedStatement(span, queryInfo);

        // TODO: factor out this code
        boolean isSqlServer = dbInfo.getType().equals("sqlserver");
        boolean injectTraceContext = DECORATE.shouldInjectTraceContext(dbInfo);
        if (isSqlServer && INJECT_COMMENT && injectTraceContext) {
          AgentSpan instrumentationSpan = startSpan("set context_info");
          if (instrumentationSpan != null) {
            DECORATE.afterStart(instrumentationSpan);
            DECORATE.onConnection(instrumentationSpan, dbInfo);
            AgentScope scope = activateSpan(instrumentationSpan);

            // TODO: remove sleep
            try {
              // Sleep for 2 seconds (2000 milliseconds)
              Thread.sleep(2000);
            } catch (InterruptedException e) {
              // Handle the interrupted exception
              System.out.println("Thread was interrupted.");
            }

            Integer priorityInstrumented;
            priorityInstrumented = span.forceSamplingDecision();
            String forceSamplingDecision = "0";
            if (priorityInstrumented > 0) {
              forceSamplingDecision = "1";
            }

            Statement instrumentationStatement = connection.createStatement();
            String instrumentationSql;
            instrumentationSql =
                    "set context_info 0x"
                            + forceSamplingDecision
                            + DDSpanId.toHexStringPadded(span.getSpanId())
                            + span.getTraceId().toHexString();
            final String originalInstrumentationSql = instrumentationSql;
            instrumentationSql =
                    SQLCommenter.inject(
                            instrumentationSql,
                            instrumentationSpan.getServiceName(),
                            dbInfo.getType(),
                            dbInfo.getHost(),
                            dbInfo.getDb(),
                            null,
                            false,
                            false);
            DECORATE.onStatement(instrumentationSpan, originalInstrumentationSql);

            instrumentationStatement.execute(instrumentationSql);
            instrumentationStatement.close();
            scope.close();
            instrumentationSpan.finish();
          }
        }


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

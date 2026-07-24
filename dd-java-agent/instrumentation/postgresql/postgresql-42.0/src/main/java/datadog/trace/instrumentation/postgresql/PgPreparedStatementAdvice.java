package datadog.trace.instrumentation.postgresql;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.postgresql.PostgreSQLDecorator.DECORATE;
import static datadog.trace.instrumentation.postgresql.PostgreSQLDecorator.JAVA_POSTGRESQL;
import static datadog.trace.instrumentation.postgresql.PostgreSQLDecorator.POSTGRESQL_QUERY;

import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.jdbc.DBInfo;
import datadog.trace.bootstrap.instrumentation.jdbc.DBQueryInfo;
import java.sql.Statement;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import net.bytebuddy.asm.Advice;

public class PgPreparedStatementAdvice {

  public static final Map<Statement, String> PREPARED_SQL =
      Collections.synchronizedMap(new WeakHashMap<Statement, String>());

  public static final class ConstructorAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(
        @Advice.This final Statement statement, @Advice.Argument(1) final Object query) {
      // In PostgreSQL JDBC 42.0+, argument[1] is CachedQuery, use toString() to get SQL
      if (query != null) {
        PREPARED_SQL.put(statement, query.toString());
      }
    }
  }

  public static final class ExecuteAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope onEnter(@Advice.This final Statement statement) {
      final int callDepth = CallDepthThreadLocalMap.incrementCallDepth(Statement.class);
      if (callDepth > 0) {
        return null;
      }

      final AgentSpan span = startSpan(JAVA_POSTGRESQL.toString(), POSTGRESQL_QUERY);
      DECORATE.afterStart(span);

      DBInfo dbInfo = InstrumentationContext.get(Statement.class, DBInfo.class).get(statement);
      if (dbInfo == null) {
        dbInfo = PgStatementAdvice.ExecuteQueryAdvice.extractDbInfo(statement);
        if (dbInfo != null) {
          InstrumentationContext.get(Statement.class, DBInfo.class).put(statement, dbInfo);
        }
      }
      if (dbInfo != null) {
        DECORATE.onConnection(span, dbInfo);
        if (dbInfo.getPort() != null) {
          DECORATE.setPeerPort(span, dbInfo.getPort());
        }
      }

      final String sql = PREPARED_SQL.get(statement);
      if (sql != null) {
        final DBQueryInfo dbQueryInfo = DBQueryInfo.ofPreparedStatement(sql);
        DECORATE.onStatement(span, dbQueryInfo.getSql());
        span.setTag(Tags.DB_OPERATION, dbQueryInfo.getOperation());

        // DBM: inject SQL comment with trace context for prepared statements
        // For prepared statements, we inject via modifying the stored SQL that will
        // be sent to the server. The comment is only added to spans' context,
        // the actual SQL modification for prepared statements happens at connection level.
        PostgreSQLSQLCommenter.inject(sql, span, dbInfo);
      }

      return activateSpan(span);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(
        @Advice.Enter final AgentScope scope, @Advice.Thrown final Throwable throwable) {
      if (scope == null) {
        return;
      }
      final AgentSpan span = scope.span();
      if (throwable != null) {
        DECORATE.onError(span, throwable);
      }
      DECORATE.beforeFinish(span);
      scope.close();
      span.finish();
      CallDepthThreadLocalMap.reset(Statement.class);
    }
  }
}

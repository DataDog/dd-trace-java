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
import datadog.trace.bootstrap.instrumentation.jdbc.JDBCConnectionUrlParser;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Statement;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import net.bytebuddy.asm.Advice;

public class PgStatementAdvice {

  public static final Map<Statement, String> BATCH_SQL =
      Collections.synchronizedMap(new WeakHashMap<Statement, String>());

  public static final class ExecuteQueryAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope onEnter(
        @Advice.This final Statement statement,
        @Advice.Argument(value = 0, readOnly = false) String sql) {
      final int callDepth = CallDepthThreadLocalMap.incrementCallDepth(Statement.class);
      if (callDepth > 0) {
        return null;
      }

      final AgentSpan span = startSpan(JAVA_POSTGRESQL.toString(), POSTGRESQL_QUERY);
      DECORATE.afterStart(span);

      DBInfo dbInfo = InstrumentationContext.get(Statement.class, DBInfo.class).get(statement);
      if (dbInfo == null) {
        dbInfo = extractDbInfo(statement);
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

      final DBQueryInfo dbQueryInfo = DBQueryInfo.ofStatement(sql);
      DECORATE.onStatement(span, dbQueryInfo.getSql());
      span.setTag(Tags.DB_OPERATION, dbQueryInfo.getOperation());

      // DBM: inject SQL comment with trace context
      sql = PostgreSQLSQLCommenter.inject(sql, span, dbInfo);

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

    public static DBInfo extractDbInfo(final Statement statement) {
      try {
        final Connection connection = statement.getConnection();
        if (connection != null) {
          final DatabaseMetaData metaData = connection.getMetaData();
          final String url = metaData.getURL();
          final String user = metaData.getUserName();
          DBInfo dbInfo = JDBCConnectionUrlParser.parse(url, null);
          if (user != null && !user.isEmpty()) {
            dbInfo = dbInfo.toBuilder().user(user).build();
          }
          return dbInfo;
        }
      } catch (final Exception ignored) {
        // Unable to extract connection info
      }
      return null;
    }
  }

  public static final class AddBatchAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(
        @Advice.This final Statement statement, @Advice.Argument(0) final String sql) {
      BATCH_SQL.put(statement, sql);
    }
  }

  public static final class ExecuteBatchAdvice {

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
        dbInfo = ExecuteQueryAdvice.extractDbInfo(statement);
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

      final String batchSql = BATCH_SQL.get(statement);
      if (batchSql != null) {
        final DBQueryInfo dbQueryInfo = DBQueryInfo.ofStatement(batchSql);
        DECORATE.onStatement(span, dbQueryInfo.getSql());
        span.setTag(Tags.DB_OPERATION, dbQueryInfo.getOperation());
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

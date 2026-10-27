package datadog.trace.instrumentation.r2dbc;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.traceConfig;
import static datadog.trace.instrumentation.r2dbc.R2dbcDecorator.DBM_ALWAYS_APPEND_SQL_COMMENT;
import static datadog.trace.instrumentation.r2dbc.R2dbcDecorator.DECORATE;
import static datadog.trace.instrumentation.r2dbc.R2dbcDecorator.INJECT_COMMENT;
import static datadog.trace.instrumentation.r2dbc.R2dbcDecorator.stringOption;

import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.dbm.SQLCommenter;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactoryOptions;

/**
 * Injects DBM SQL comments into R2DBC queries using the same {@link SQLCommenter} as JDBC.
 *
 * <p>Injection happens on the real driver's {@code Connection#createStatement(String)} (see {@link
 * R2dbcConnectionInstrumentation}), mirroring JDBC's {@code Connection#prepareStatement} advice. It
 * only ever embeds static, per-connection metadata (service, db type, host, db name), never a
 * per-execution traceparent — R2DBC has no interception point between statement creation and
 * execution where the query span already exists, so (like JDBC's prepare-time injection) it defers
 * dynamic trace context.
 */
public final class R2dbcSqlCommentInjector {

  private R2dbcSqlCommentInjector() {}

  /**
   * Resolves connection metadata for {@code connection} (via {@code connectionOptionsStore}) and
   * injects a DBM SQL comment into {@code sql} if DBM propagation is enabled. Called from {@link
   * R2dbcConnectionInstrumentation}'s advice on the real driver's {@code
   * Connection#createStatement(String)}.
   *
   * @return the SQL with injected comment, or the original SQL if DBM is disabled or metadata is
   *     unavailable
   */
  public static String injectForConnection(
      String sql,
      Connection connection,
      ContextStore<Connection, ConnectionFactoryOptions> connectionOptionsStore) {
    if (!INJECT_COMMENT) {
      return sql;
    }

    ConnectionFactoryOptions options = connectionOptionsStore.get(connection);
    if (options == null) {
      return sql;
    }

    return inject(sql, options);
  }

  static String inject(String sql, ConnectionFactoryOptions options) {
    String dbType = DECORATE.getDbType(options);
    String dbService = DECORATE.getDbService(options);
    if (dbService != null) {
      dbService = traceConfig(activeSpan()).getServiceMapping().getOrDefault(dbService, dbService);
    }
    String hostname = stringOption(options, ConnectionFactoryOptions.HOST);
    String dbName = stringOption(options, ConnectionFactoryOptions.DATABASE);

    return inject(sql, dbService, dbType, hostname, dbName);
  }

  /**
   * Injects a DBM SQL comment into the given query string if DBM propagation is enabled.
   *
   * @param sql the original SQL query
   * @param dbService the database service name for dddbs
   * @param dbType the database type (e.g. "h2", "postgresql")
   * @param hostname the database hostname
   * @param dbName the database name
   * @return the SQL with injected comment, or the original SQL if DBM is disabled
   */
  public static String inject(
      String sql, String dbService, String dbType, String hostname, String dbName) {
    if (!INJECT_COMMENT) {
      return sql;
    }
    // No traceparent: see the class-level javadoc.
    return SQLCommenter.inject(
        sql, dbService, dbType, hostname, dbName, null, preferAppend(dbType));
  }

  /**
   * Reasons to append that the caller decides (statement-shape reasons such as {@code CALL} or a
   * {@code pg_hint_plan} hint are applied by {@link SQLCommenter}). SQL Server appends at statement
   * creation, as JDBC does for {@code prepareStatement}.
   */
  static boolean preferAppend(String dbType) {
    return DBM_ALWAYS_APPEND_SQL_COMMENT || "sqlserver".equals(dbType) || "mssql".equals(dbType);
  }
}

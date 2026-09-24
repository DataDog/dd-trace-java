package datadog.trace.instrumentation.r2dbc;

import static datadog.trace.instrumentation.r2dbc.R2dbcDecorator.DECORATE;

import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.dbm.SharedDBCommenter;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactoryOptions;

/**
 * Injects DBM SQL comments into R2DBC queries. Reuses {@link SharedDBCommenter} to build the
 * comment content (service metadata) and wraps it in SQL comment delimiters.
 *
 * <p>This is the R2DBC equivalent of JDBC's {@code SQLCommenter}. It is intentionally simpler
 * because R2DBC does not have the same edge cases (callable statements, pg_hint_plan) as JDBC.
 *
 * <p>Injection happens on the real driver's {@code Connection#createStatement(String)} (see {@link
 * R2dbcConnectionInstrumentation}), mirroring JDBC's {@code Connection#prepareStatement} advice. It
 * only ever embeds static, per-connection metadata (service, db type, host, db name), never a
 * per-execution traceparent — R2DBC has no interception point between statement creation and
 * execution where the query span already exists, so (like JDBC's prepare-time injection) it defers
 * dynamic trace context.
 */
public final class R2dbcSqlCommentInjector {

  private static final String OPEN_COMMENT = "/*";
  private static final String CLOSE_COMMENT = "*/";

  private R2dbcSqlCommentInjector() {}

  /**
   * Resolves connection metadata for {@code connection} (via {@link R2dbcConnectionMetadataStore})
   * and injects a DBM SQL comment into {@code sql} if DBM propagation is enabled. Called from
   * {@link R2dbcConnectionInstrumentation}'s advice on the real driver's {@code
   * Connection#createStatement(String)}.
   *
   * @return the SQL with injected comment, or the original SQL if DBM is disabled or metadata is
   *     unavailable
   */
  public static String injectForConnection(String sql, Connection connection) {
    String dbmMode = Config.get().getDbmPropagationMode();
    boolean injectComment =
        Config.DBM_PROPAGATION_MODE_FULL.equals(dbmMode)
            || Config.DBM_PROPAGATION_MODE_STATIC.equals(dbmMode)
            || Config.DBM_PROPAGATION_MODE_DYNAMIC_SERVICE.equals(dbmMode);
    if (!injectComment) {
      return sql;
    }

    ConnectionFactoryOptions options = R2dbcConnectionMetadataStore.get(connection);
    if (options == null) {
      return sql;
    }

    String dbType = DECORATE.extractDbType(options);
    String dbService = DECORATE.getDbService(options);
    String hostname = null;
    if (options.hasOption(ConnectionFactoryOptions.HOST)) {
      Object host = options.getValue(ConnectionFactoryOptions.HOST);
      hostname = host != null ? host.toString() : null;
    }
    String dbName = null;
    if (options.hasOption(ConnectionFactoryOptions.DATABASE)) {
      Object db = options.getValue(ConnectionFactoryOptions.DATABASE);
      dbName = db != null ? db.toString() : null;
    }

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
    if (sql == null || sql.isEmpty()) {
      return sql;
    }

    String dbmMode = Config.get().getDbmPropagationMode();
    boolean injectComment =
        Config.DBM_PROPAGATION_MODE_FULL.equals(dbmMode)
            || Config.DBM_PROPAGATION_MODE_STATIC.equals(dbmMode)
            || Config.DBM_PROPAGATION_MODE_DYNAMIC_SERVICE.equals(dbmMode);

    if (!injectComment) {
      return sql;
    }

    // No traceparent: see the class-level javadoc for why per-execution trace context can't
    // be injected at this point.
    String commentContent =
        SharedDBCommenter.buildComment(dbService, dbType, hostname, dbName, null);
    if (commentContent == null) {
      return sql;
    }

    // Check for existing DD comment to avoid duplicate injection
    if (sql.startsWith(OPEN_COMMENT) && SharedDBCommenter.containsTraceComment(sql)) {
      return sql;
    }

    // Prepend the comment to the SQL query
    StringBuilder sb = new StringBuilder(sql.length() + commentContent.length() + 6);
    sb.append(OPEN_COMMENT);
    sb.append(commentContent);
    sb.append(CLOSE_COMMENT);
    sb.append(' ');
    sb.append(sql);
    return sb.toString();
  }
}

package datadog.trace.instrumentation.r2dbc;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.traceConfig;
import static datadog.trace.instrumentation.r2dbc.R2dbcDecorator.DBM_ALWAYS_APPEND_SQL_COMMENT;
import static datadog.trace.instrumentation.r2dbc.R2dbcDecorator.DECORATE;
import static datadog.trace.instrumentation.r2dbc.R2dbcDecorator.INJECT_COMMENT;
import static datadog.trace.instrumentation.r2dbc.R2dbcDecorator.stringOption;

import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.dbm.SharedDBCommenter;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactoryOptions;

/**
 * Injects DBM SQL comments into R2DBC queries. Reuses {@link SharedDBCommenter} to build the
 * comment content (service metadata) and wraps it in SQL comment delimiters.
 *
 * <p>This is the R2DBC equivalent of JDBC's {@code SQLCommenter} and follows the same placement
 * rules: the comment is prepended, unless {@code dd.dbm.always_append_sql_comment} is set, the
 * statement is a {@code CALL}, it carries a PostgreSQL {@code pg_hint_plan} hint ({@code /*+}), or
 * the database is SQL Server, in which case it is appended. JDBC's {@code {call ...}} escape syntax
 * has no R2DBC equivalent, and the Oracle {@code v$session.action} service-hash mode does not apply
 * because R2DBC never sets the session action.
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
    return inject(sql, dbService, dbType, hostname, dbName, DBM_ALWAYS_APPEND_SQL_COMMENT);
  }

  static String inject(
      String sql,
      String dbService,
      String dbType,
      String hostname,
      String dbName,
      boolean preferAppend) {
    if (sql == null || sql.isEmpty()) {
      return sql;
    }

    if (!INJECT_COMMENT) {
      return sql;
    }

    boolean appendComment = preferAppend || mustAppend(sql, dbType);

    // Skip SQL that already carries a DD comment before building a new one
    if (hasDDComment(sql, appendComment)) {
      return sql;
    }

    // No traceparent: see the class-level javadoc for why per-execution trace context can't
    // be injected at this point.
    String commentContent =
        SharedDBCommenter.buildComment(dbService, dbType, hostname, dbName, null);
    if (commentContent == null) {
      return sql;
    }

    StringBuilder sb = new StringBuilder(sql.length() + commentContent.length() + 6);
    if (appendComment) {
      // Keep a statement-terminating semicolon after the comment
      int closingSemicolon = indexOfClosingSemicolon(sql);
      sb.append(sql, 0, closingSemicolon > -1 ? closingSemicolon : sql.length());
      sb.append(' ').append(OPEN_COMMENT).append(commentContent).append(CLOSE_COMMENT);
      if (closingSemicolon > -1) {
        sb.append(';');
      }
    } else {
      sb.append(OPEN_COMMENT).append(commentContent).append(CLOSE_COMMENT).append(' ').append(sql);
    }
    return sb.toString();
  }

  /**
   * PostgreSQL and MySQL reject anything before {@code CALL}, and {@code pg_hint_plan} only reads a
   * hint comment at the start of the statement, so both must keep the DD comment at the end. SQL
   * Server always appends at statement creation, as JDBC does for {@code prepareStatement}.
   */
  private static boolean mustAppend(String sql, String dbType) {
    if (startsWithIgnoreCase(sql, "call")) {
      return true;
    }
    if (dbType == null) {
      return false;
    }
    return "sqlserver".equals(dbType)
        || "mssql".equals(dbType)
        || (dbType.startsWith("postgres") && sql.contains("/*+"));
  }

  private static boolean startsWithIgnoreCase(String sql, String word) {
    int start = 0;
    while (start < sql.length() && Character.isWhitespace(sql.charAt(start))) {
      start++;
    }
    int end = start + word.length();
    return sql.regionMatches(true, start, word, 0, word.length())
        && (end == sql.length() || Character.isWhitespace(sql.charAt(end)));
  }

  private static boolean hasDDComment(String sql, boolean appendComment) {
    if (appendComment) {
      // Look at the last comment, ignoring a terminating semicolon and trailing whitespace
      int tail = indexOfClosingSemicolon(sql);
      int bodyEnd = tail > -1 ? tail : sql.length();
      while (bodyEnd > 0 && Character.isWhitespace(sql.charAt(bodyEnd - 1))) {
        bodyEnd--;
      }
      int end = bodyEnd - CLOSE_COMMENT.length();
      if (end < 0 || !sql.startsWith(CLOSE_COMMENT, end)) {
        return false;
      }
      int start = sql.lastIndexOf(OPEN_COMMENT, end - 1);
      return start != -1
          && SharedDBCommenter.containsTraceComment(sql, start + OPEN_COMMENT.length(), end);
    }
    if (!sql.startsWith(OPEN_COMMENT)) {
      return false;
    }
    int end = sql.indexOf(CLOSE_COMMENT, OPEN_COMMENT.length());
    return end != -1 && SharedDBCommenter.containsTraceComment(sql, OPEN_COMMENT.length(), end);
  }

  /** Index of the semicolon that terminates {@code sql} (ignoring trailing whitespace), or -1. */
  private static int indexOfClosingSemicolon(String sql) {
    for (int i = sql.length() - 1; i >= 0; i--) {
      char c = sql.charAt(i);
      if (c == ';') {
        return i;
      } else if (!Character.isWhitespace(c)) {
        break;
      }
    }
    return -1;
  }
}

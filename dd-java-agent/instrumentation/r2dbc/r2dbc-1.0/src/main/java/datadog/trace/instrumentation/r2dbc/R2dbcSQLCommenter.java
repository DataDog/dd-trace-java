package datadog.trace.instrumentation.r2dbc;

import datadog.trace.bootstrap.instrumentation.dbm.SharedDBCommenter;

/**
 * Injects DBM trace context as SQL comments for R2DBC queries. Delegates comment content building
 * to {@link SharedDBCommenter} and wraps the result in SQL block comment delimiters.
 */
public final class R2dbcSQLCommenter {
  private static final String OPEN_COMMENT = "/*";
  private static final String CLOSE_COMMENT = "*/";

  private R2dbcSQLCommenter() {}

  /**
   * Injects a DBM SQL comment into the given query. By default the comment is prepended.
   *
   * @param sql the original SQL string
   * @param dbService the database service name
   * @param dbType the database type (e.g. "postgresql", "mysql")
   * @param hostname the database hostname
   * @param dbName the database name
   * @param traceParent the W3C traceparent string (null for service-only mode)
   * @return the SQL with injected comment, or the original if injection is not applicable
   */
  public static String inject(
      String sql,
      String dbService,
      String dbType,
      String hostname,
      String dbName,
      String traceParent) {
    if (sql == null || sql.isEmpty()) {
      return sql;
    }
    // Check if a DD comment is already present
    if (hasDDComment(sql)) {
      return sql;
    }
    String commentContent =
        SharedDBCommenter.buildComment(dbService, dbType, hostname, dbName, traceParent);
    if (commentContent == null) {
      return sql;
    }
    // Prepend the comment to the SQL
    StringBuilder sb = new StringBuilder(sql.length() + commentContent.length() + 5);
    sb.append(OPEN_COMMENT);
    sb.append(commentContent);
    sb.append(CLOSE_COMMENT);
    sb.append(' ');
    sb.append(sql);
    return sb.toString();
  }

  private static boolean hasDDComment(String sql) {
    if (!sql.startsWith(OPEN_COMMENT)) {
      return false;
    }
    int endIdx = sql.indexOf(CLOSE_COMMENT);
    if (endIdx > 2) {
      return SharedDBCommenter.containsTraceComment(sql, 2, endIdx);
    }
    return false;
  }
}

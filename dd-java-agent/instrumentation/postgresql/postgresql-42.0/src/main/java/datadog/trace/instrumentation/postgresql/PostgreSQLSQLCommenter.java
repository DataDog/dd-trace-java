package datadog.trace.instrumentation.postgresql;

import static datadog.trace.api.Config.DBM_PROPAGATION_MODE_FULL;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.DBM_TRACE_INJECTED;

import datadog.trace.api.Config;
import datadog.trace.api.propagation.W3CTraceParent;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.dbm.SharedDBCommenter;
import datadog.trace.bootstrap.instrumentation.jdbc.DBInfo;

/**
 * SQL comment injector for PostgreSQL Database Monitoring (DBM). Prepends a {@code /* ... * /}
 * comment to SQL queries containing trace context metadata so the database can correlate queries
 * back to traces.
 */
public class PostgreSQLSQLCommenter {

  /**
   * Prepends a DBM trace comment to the given SQL string. Returns the original SQL unchanged if DBM
   * comment injection is disabled or if the SQL already contains a trace comment.
   *
   * @param sql the original SQL string
   * @param span the active span for trace context
   * @param dbInfo the database connection info (may be null)
   * @return the SQL string with a prepended trace comment, or the original SQL if injection is not
   *     applicable
   */
  public static String inject(String sql, AgentSpan span, DBInfo dbInfo) {
    if (!Config.get().isDbmCommentInjectionEnabled() || sql == null || sql.isEmpty()) {
      return sql;
    }

    if (span == null) {
      return sql;
    }

    // Force a sampling decision so traceparent has a valid sampling flag
    if (span.forceSamplingDecision() == null) {
      return sql;
    }

    // Check if the SQL already has a trace comment to avoid duplicate injection
    if (hasExistingTraceComment(sql)) {
      return sql;
    }

    String dbService = span.getServiceName();
    String hostname = dbInfo != null ? dbInfo.getHost() : null;
    String dbName = dbInfo != null ? dbInfo.getDb() : null;

    String traceParent =
        Config.get().getDbmPropagationMode().equals(DBM_PROPAGATION_MODE_FULL)
            ? W3CTraceParent.from(span)
            : null;

    String comment =
        SharedDBCommenter.buildComment(dbService, "postgresql", hostname, dbName, traceParent);

    if (comment == null || comment.isEmpty()) {
      return sql;
    }

    // Set the DBM trace injected tag on the span
    span.setTag(DBM_TRACE_INJECTED, true);

    // Prepend the comment as a SQL block comment
    return "/*" + comment + "*/ " + sql;
  }

  /** Checks if the given SQL string already contains a DBM trace comment. */
  private static boolean hasExistingTraceComment(String sql) {
    // Look for an opening block comment and check if it contains trace comment markers
    int commentStart = sql.indexOf("/*");
    if (commentStart < 0) {
      return false;
    }
    int commentEnd = sql.indexOf("*/", commentStart + 2);
    if (commentEnd < 0) {
      return false;
    }
    // Check the comment body for trace comment fields
    return SharedDBCommenter.containsTraceComment(sql, commentStart + 2, commentEnd);
  }
}

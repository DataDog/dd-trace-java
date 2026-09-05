package datadog.trace.instrumentation.r2dbc;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;

import datadog.trace.api.Config;
import datadog.trace.api.propagation.W3CTraceParent;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.dbm.SharedDBCommenter;

/**
 * Injects DBM SQL comments into R2DBC queries. Reuses {@link SharedDBCommenter} to build the
 * comment content (service metadata, trace context) and wraps it in SQL comment delimiters.
 *
 * <p>This is the R2DBC equivalent of JDBC's {@code SQLCommenter}. It is intentionally simpler
 * because R2DBC does not have the same edge cases (callable statements, pg_hint_plan) as JDBC.
 */
public final class R2dbcSqlCommentInjector {

  private static final String OPEN_COMMENT = "/*";
  private static final String CLOSE_COMMENT = "*/";

  private R2dbcSqlCommentInjector() {}

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

    // Generate traceparent only in full mode
    String traceParent = null;
    if (Config.DBM_PROPAGATION_MODE_FULL.equals(dbmMode)) {
      AgentSpan activeSpan = activeSpan();
      if (activeSpan != null) {
        Integer priority = activeSpan.forceSamplingDecision();
        if (priority != null) {
          traceParent = W3CTraceParent.from(activeSpan);
        }
      }
    }

    String commentContent =
        SharedDBCommenter.buildComment(dbService, dbType, hostname, dbName, traceParent);
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

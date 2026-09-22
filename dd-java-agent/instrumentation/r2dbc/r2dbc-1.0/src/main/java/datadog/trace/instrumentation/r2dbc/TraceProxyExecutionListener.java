package datadog.trace.instrumentation.r2dbc;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.r2dbc.R2dbcDecorator.DECORATE;
import static datadog.trace.instrumentation.r2dbc.R2dbcDecorator.R2DBC_QUERY;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.dbm.SharedDBCommenter;
import datadog.trace.bootstrap.instrumentation.jdbc.DBQueryInfo;
import io.r2dbc.proxy.core.QueryExecutionInfo;
import io.r2dbc.proxy.core.QueryInfo;
import io.r2dbc.proxy.listener.ProxyExecutionListener;
import io.r2dbc.spi.ConnectionFactoryOptions;
import java.util.List;

/**
 * R2DBC proxy listener that creates database spans around query executions. The r2dbc-proxy
 * framework owns the reactive lifecycle (complete/error/cancel), so this listener does not need to
 * handle cancellation — the {@code afterQuery} callback fires in all cases.
 *
 * <p>When Database Monitoring (DBM) is enabled via {@code dd.dbm.propagation.mode}, this listener
 * reflects whether a {@code _dd.dbm_trace_injected} comment is actually present on the executed
 * query. SQL comment injection itself happens earlier, at statement/batch creation time (see {@link
 * R2dbcConnectionCallbackInstrumentation} and {@link R2dbcBatchCallbackInstrumentation}).
 */
public final class TraceProxyExecutionListener implements ProxyExecutionListener {

  private static final String SPAN_KEY = "datadog.span";
  private static final String DBM_TRACE_INJECTED = "_dd.dbm_trace_injected";

  private final ConnectionFactoryOptions options;

  public TraceProxyExecutionListener(ConnectionFactoryOptions options) {
    this.options = options;
  }

  @Override
  public void beforeQuery(QueryExecutionInfo execInfo) {
    AgentSpan span = startSpan("r2dbc", R2DBC_QUERY);
    DECORATE.afterStart(span);

    String dbType = DECORATE.extractDbType(options);
    DECORATE.applyDatabaseType(span, dbType);
    DECORATE.onConnection(span, options);

    String queryString = extractQuery(execInfo);
    if (queryString != null) {
      // Reflect whether a DBM comment was actually injected for THIS query, rather than
      // asserting it unconditionally. Injection happens earlier, at statement/batch creation
      // time (see R2dbcConnectionCallbackInstrumentation / R2dbcBatchCallbackInstrumentation) —
      // by the time this span exists the query text already carries the comment if injection
      // succeeded, so inspecting it here is the only reliable per-query signal.
      if (SharedDBCommenter.containsTraceComment(queryString)) {
        span.setTag(DBM_TRACE_INJECTED, true);
      }

      // Route through DBQueryInfo/SQLNormalizer (same as JDBC/Vert.x) instead of using the raw
      // query string as the resource name — this strips literals/numbers for grouping and
      // avoids leaking parameter values into the resource name. Strip any DBM comment first so
      // it doesn't end up in the resource name (JDBC captures the pre-injection SQL instead;
      // R2DBC's listener only sees the already-injected wire SQL, so we remove it here).
      DBQueryInfo queryInfo = DBQueryInfo.ofStatement(stripLeadingComment(queryString));
      span.setResourceName(queryInfo.getSql());
    }

    span.setMeasured(true);
    execInfo.getValueStore().put(SPAN_KEY, span);
  }

  @Override
  public void afterQuery(QueryExecutionInfo execInfo) {
    AgentSpan span = execInfo.getValueStore().get(SPAN_KEY, AgentSpan.class);
    if (span == null) {
      return;
    }
    if (execInfo.getThrowable() != null) {
      DECORATE.onError(span, execInfo.getThrowable());
    }
    DECORATE.beforeFinish(span);
    span.finish();
  }

  /**
   * Strips a single leading block comment (and any whitespace after it) from {@code sql}. Used to
   * remove the DBM comment this instrumentation injects at statement-creation time before deriving
   * the span resource name, so the comment doesn't leak into it.
   */
  private static String stripLeadingComment(String sql) {
    if (sql == null || !sql.startsWith("/*")) {
      return sql;
    }
    int end = sql.indexOf("*/");
    if (end < 0) {
      return sql;
    }
    int i = end + 2;
    while (i < sql.length() && Character.isWhitespace(sql.charAt(i))) {
      i++;
    }
    return sql.substring(i);
  }

  private static String extractQuery(QueryExecutionInfo execInfo) {
    List<QueryInfo> queries = execInfo.getQueries();
    if (queries == null || queries.isEmpty()) {
      return null;
    }
    if (queries.size() == 1) {
      return queries.get(0).getQuery();
    }
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < queries.size(); i++) {
      if (i > 0) {
        sb.append("; ");
      }
      sb.append(queries.get(i).getQuery());
    }
    return sb.toString();
  }
}

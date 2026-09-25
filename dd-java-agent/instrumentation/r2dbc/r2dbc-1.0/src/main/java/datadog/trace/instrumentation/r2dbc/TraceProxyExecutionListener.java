package datadog.trace.instrumentation.r2dbc;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.r2dbc.R2dbcDecorator.DECORATE;
import static datadog.trace.instrumentation.r2dbc.R2dbcDecorator.R2DBC_QUERY;

import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
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
 * sets the {@code _dd.dbm_trace_injected} tag. The SQL comment itself is injected on the real
 * driver's {@code Connection#createStatement} (see {@link R2dbcConnectionInstrumentation}); that
 * runs downstream of the proxy, so the query text this listener observes does not carry the comment
 * — the tag is therefore driven by the same DBM-mode gate the injector uses, not by inspecting the
 * observed SQL.
 */
public final class TraceProxyExecutionListener implements ProxyExecutionListener {

  private static final String SPAN_KEY = "datadog.span";
  private static final String DBM_TRACE_INJECTED = "_dd.dbm_trace_injected";

  private final ConnectionFactoryOptions options;

  public TraceProxyExecutionListener(ConnectionFactoryOptions options) {
    this.options = options;
  }

  private static boolean dbmInjectionEnabled() {
    String dbmMode = Config.get().getDbmPropagationMode();
    return Config.DBM_PROPAGATION_MODE_FULL.equals(dbmMode)
        || Config.DBM_PROPAGATION_MODE_STATIC.equals(dbmMode)
        || Config.DBM_PROPAGATION_MODE_DYNAMIC_SERVICE.equals(dbmMode);
  }

  @Override
  public void beforeQuery(QueryExecutionInfo execInfo) {
    AgentSpan span = startSpan("r2dbc", R2DBC_QUERY);
    DECORATE.afterStart(span);

    String dbType = DECORATE.extractDbType(options);
    DECORATE.applyDatabaseType(span, dbType);
    DECORATE.onConnection(span, options);

    // Mirror the injector's gate: R2dbcConnectionInstrumentation injects the DBM comment on the
    // real driver's createStatement whenever DBM propagation is enabled, so the tag reflects that
    // same condition. (The proxy observes the pre-injection SQL, so we cannot detect the comment
    // here — this is the same approach JDBC uses, driving the tag off DBM state.)
    if (dbmInjectionEnabled()) {
      span.setTag(DBM_TRACE_INJECTED, true);
    }

    String queryString = extractQuery(execInfo);
    if (queryString != null) {
      // Route through DBQueryInfo/SQLNormalizer (same as JDBC/Vert.x) instead of using the raw
      // query string as the resource name — this strips literals/numbers for grouping and avoids
      // leaking parameter values into the resource name.
      DBQueryInfo queryInfo = DBQueryInfo.ofStatement(queryString);
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

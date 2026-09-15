package datadog.trace.instrumentation.r2dbc;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.r2dbc.R2dbcDecorator.DECORATE;
import static datadog.trace.instrumentation.r2dbc.R2dbcDecorator.R2DBC_QUERY;

import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
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
 * also sets the {@code _dd.dbm_trace_injected} tag on spans. SQL comment injection is handled
 * separately by {@link R2dbcConnectionCallbackInstrumentation}.
 */
public final class TraceProxyExecutionListener implements ProxyExecutionListener {

  private static final String SPAN_KEY = "datadog.span";
  private static final String DBM_TRACE_INJECTED = "_dd.dbm_trace_injected";

  private final ConnectionFactoryOptions options;
  private final boolean injectTraceContext;

  public TraceProxyExecutionListener(ConnectionFactoryOptions options) {
    this.options = options;
    String dbmMode = Config.get().getDbmPropagationMode();
    this.injectTraceContext = Config.DBM_PROPAGATION_MODE_FULL.equals(dbmMode);
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
      DECORATE.onStatement(span, queryString);
    }

    if (injectTraceContext) {
      Integer priority = span.forceSamplingDecision();
      if (priority != null) {
        span.setTag(DBM_TRACE_INJECTED, true);
      }
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

package datadog.trace.instrumentation.r2dbc;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.bootstrap.instrumentation.api.Tags.DB_OPERATION;
import static datadog.trace.instrumentation.r2dbc.R2dbcDecorator.DB_QUERY;
import static datadog.trace.instrumentation.r2dbc.R2dbcDecorator.DECORATE;
import static datadog.trace.instrumentation.r2dbc.R2dbcDecorator.R2DBC_QUERY;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.jdbc.DBQueryInfo;
import datadog.trace.instrumentation.r2dbc.shaded.proxy.core.QueryExecutionInfo;
import datadog.trace.instrumentation.r2dbc.shaded.proxy.core.QueryInfo;
import datadog.trace.instrumentation.r2dbc.shaded.proxy.listener.ProxyExecutionListener;
import io.r2dbc.spi.ConnectionFactoryOptions;
import java.util.List;

/**
 * R2DBC proxy listener that creates database spans around query executions. The r2dbc-proxy
 * framework owns the reactive lifecycle (complete/error/cancel), so this listener does not need to
 * handle cancellation — the {@code afterQuery} callback fires in all cases.
 */
public final class TraceProxyExecutionListener implements ProxyExecutionListener {

  private static final String SPAN_KEY = "datadog.span";

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
    DECORATE.withBaseHash(span);

    String queryString = extractQuery(execInfo);
    if (queryString != null) {
      // Route through DBQueryInfo/SQLNormalizer (same as JDBC/Vert.x) instead of using the raw
      // query string as the resource name — this strips literals/numbers for grouping and avoids
      // leaking parameter values into the resource name.

      // R2DBC commonly re-executes bind-parameterized SQL templates; use the bounded
      // prepared-statement cache to avoid normalizing the same query text per execution.
      DBQueryInfo queryInfo = DBQueryInfo.ofPreparedStatement(queryString);
      span.setResourceName(queryInfo.getSql());
      span.setTag(DB_OPERATION, queryInfo.getOperation());
    } else {
      span.setResourceName(DB_QUERY);
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

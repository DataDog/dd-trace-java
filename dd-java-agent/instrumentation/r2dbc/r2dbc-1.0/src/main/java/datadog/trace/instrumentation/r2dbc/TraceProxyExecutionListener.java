package datadog.trace.instrumentation.r2dbc;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.bootstrap.instrumentation.api.Tags.DB_OPERATION;
import static datadog.trace.instrumentation.r2dbc.R2dbcDecorator.DB_QUERY;
import static datadog.trace.instrumentation.r2dbc.R2dbcDecorator.DECORATE;
import static datadog.trace.instrumentation.r2dbc.R2dbcDecorator.R2DBC_QUERY;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.jdbc.DBQueryInfo;
import datadog.trace.instrumentation.r2dbc.shaded.proxy.core.QueryExecutionInfo;
import datadog.trace.instrumentation.r2dbc.shaded.proxy.core.QueryInfo;
import datadog.trace.instrumentation.r2dbc.shaded.proxy.listener.ProxyExecutionListener;
import io.r2dbc.spi.ConnectionFactoryOptions;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * R2DBC proxy listener that creates database spans around query executions. The r2dbc-proxy
 * framework owns the reactive lifecycle (complete/error/cancel), so this listener does not need to
 * handle cancellation — the {@code afterQuery} callback fires in all cases.
 */
public final class TraceProxyExecutionListener implements ProxyExecutionListener {

  private static final String SPAN_KEY = "datadog.span";
  static final int MAX_BATCH_STATEMENTS_IN_RESOURCE = 10;

  private final ConnectionFactoryOptions options;

  public TraceProxyExecutionListener(ConnectionFactoryOptions options) {
    this.options = options;
  }

  @Override
  public void beforeQuery(QueryExecutionInfo execInfo) {
    AgentSpan span = startSpan("r2dbc", R2DBC_QUERY);
    DECORATE.afterStart(span);

    String dbType = DECORATE.getDbType(options);
    DECORATE.applyDatabaseType(span, dbType);
    DECORATE.onConnection(span, options);
    DECORATE.withBaseHash(span);

    List<QueryInfo> queries = execInfo.getQueries();
    String firstQuery = queries == null || queries.isEmpty() ? null : queries.get(0).getQuery();
    if (firstQuery != null) {
      // Route through DBQueryInfo/SQLNormalizer (same as JDBC/Vert.x) instead of using the raw
      // query string as the resource name — this strips literals/numbers for grouping and avoids
      // leaking parameter values into the resource name.

      // R2DBC commonly re-executes bind-parameterized SQL templates; use the bounded
      // prepared-statement cache to avoid normalizing the same query text per execution.
      // Batch entries skip the cache: with inlined literals they rarely repeat and would evict
      // reusable templates.
      boolean single = queries.size() == 1;
      DBQueryInfo queryInfo =
          single
              ? DBQueryInfo.ofPreparedStatement(firstQuery)
              : DBQueryInfo.ofStatement(firstQuery);
      span.setResourceName(single ? queryInfo.getSql() : batchResource(queries, queryInfo));
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

  /**
   * A {@link io.r2dbc.spi.Batch} can hold thousands of statements, and this runs on the thread
   * executing the query (typically an I/O event loop). Summarize it from its first {@value
   * MAX_BATCH_STATEMENTS_IN_RESOURCE} statements only, as their distinct normalized forms, so the
   * cost does not grow with the batch.
   */
  private static String batchResource(List<QueryInfo> queries, DBQueryInfo first) {
    Set<UTF8BytesString> distinct = new LinkedHashSet<>();
    distinct.add(first.getSql());
    int limit = Math.min(queries.size(), MAX_BATCH_STATEMENTS_IN_RESOURCE);
    for (int i = 1; i < limit; i++) {
      String query = queries.get(i).getQuery();
      if (query != null) {
        distinct.add(DBQueryInfo.ofStatement(query).getSql());
      }
    }
    return String.join("; ", distinct);
  }
}

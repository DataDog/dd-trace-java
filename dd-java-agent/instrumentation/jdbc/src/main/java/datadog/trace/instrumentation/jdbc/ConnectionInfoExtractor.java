package datadog.trace.instrumentation.jdbc;

import static datadog.trace.bootstrap.instrumentation.api.Tags.DB_POOL_NAME;
import static datadog.trace.bootstrap.instrumentation.api.Tags.DB_SCHEMA;
import static datadog.trace.bootstrap.instrumentation.api.Tags.DB_WAREHOUSE;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.TagExtractor;
import datadog.trace.bootstrap.instrumentation.jdbc.DBInfo;

/**
 * Named singleton {@link TagExtractor} for the pure JDBC connection tags (warehouse / schema /
 * pool) — no derivation, no side-effects.
 *
 * <p>Promoted from an inline lambda in {@link JDBCDecorator} to a named class so it can be
 * referenced by name at call sites ({@code span.setTags(dbInfo, ConnectionInfoExtractor.INSTANCE)})
 * and composed with other extractors. Non-capturing and stateless — the single {@link #INSTANCE} is
 * effectively a static function object, so a monomorphic call site inlines it away.
 * Behavior-identical to the previous inline {@code setTagIfPresent} block. ({@code db.type} /
 * instance / hostname stay in the decorator for now: their values feed service-name/naming
 * derivation, a later declarative step.)
 */
public final class ConnectionInfoExtractor implements TagExtractor<DBInfo> {
  public static final ConnectionInfoExtractor INSTANCE = new ConnectionInfoExtractor();

  private ConnectionInfoExtractor() {}

  @Override
  public void extract(final DBInfo info, final AgentSpan span) {
    setTagIfPresent(span, DB_WAREHOUSE, info.getWarehouse());
    setTagIfPresent(span, DB_SCHEMA, info.getSchema());
    setTagIfPresent(span, DB_POOL_NAME, info.getPoolName());
  }

  private static void setTagIfPresent(final AgentSpan span, final String key, final String value) {
    if (value != null && !value.isEmpty()) {
      span.setTag(key, value);
    }
  }
}

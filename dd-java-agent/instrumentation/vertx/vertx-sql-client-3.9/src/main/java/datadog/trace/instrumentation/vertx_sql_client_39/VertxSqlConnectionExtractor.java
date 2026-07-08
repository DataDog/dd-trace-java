package datadog.trace.instrumentation.vertx_sql_client_39;

import static datadog.trace.bootstrap.instrumentation.api.Tags.DB_USER;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.TagExtractor;
import datadog.trace.bootstrap.instrumentation.jdbc.DBInfo;

/**
 * Named singleton {@link TagExtractor} for Vertx-SQL's pure connection tags ({@code db.user}). The
 * SQL-family counterpart to the NoSQL stores that contribute none — injected via the param form of
 * {@code DatabaseClientDecorator.onConnection} rather than the removed {@code dbUser} template
 * method.
 */
public final class VertxSqlConnectionExtractor implements TagExtractor<DBInfo> {
  public static final VertxSqlConnectionExtractor INSTANCE = new VertxSqlConnectionExtractor();

  private VertxSqlConnectionExtractor() {}

  @Override
  public void extract(final DBInfo info, final AgentSpan span) {
    // Unconditional to preserve the prior DatabaseClientDecorator behavior (db.user was set from
    // dbUser(info) with no present-check).
    span.setTag(DB_USER, info.getUser());
  }
}

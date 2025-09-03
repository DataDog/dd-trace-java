package datadog.trace.instrumentation.datastax.cassandra;

import static datadog.trace.bootstrap.instrumentation.api.Tags.DB_INSTANCE;

import com.datastax.driver.core.ColumnDefinitions;
import com.datastax.driver.core.Host;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Session;
import datadog.trace.api.Config;
import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import datadog.trace.api.naming.SpanNaming;
import datadog.trace.api.normalize.SQLNormalizer;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.DBTypeProcessingDatabaseClientDecorator;
import datadog.trace.util.Strings;
import java.util.function.ToIntFunction;

public class CassandraClientDecorator extends DBTypeProcessingDatabaseClientDecorator<Session> {
  private static final String DB_TYPE = "cassandra";
  private static final String SERVICE_NAME =
      SpanNaming.instance().namingSchema().database().service(DB_TYPE);
  public static final CharSequence OPERATION_NAME =
      UTF8BytesString.create(SpanNaming.instance().namingSchema().database().operation(DB_TYPE));
  public static final CharSequence JAVA_CASSANDRA = UTF8BytesString.create("java-cassandra");

  public static final CassandraClientDecorator DECORATE = new CassandraClientDecorator();

  private static final int COMBINED_STATEMENT_LIMIT = 2 * 1024 * 1024; // chars
  private static final ToIntFunction<UTF8BytesString> STATEMENT_WEIGHER = UTF8BytesString::length;
  private static final DDCache<CharSequence, UTF8BytesString> CACHED_STATEMENTS =
      DDCaches.newFixedSizeWeightedCache(512, STATEMENT_WEIGHER, COMBINED_STATEMENT_LIMIT);

  protected static UTF8BytesString normalizedQuery(CharSequence sql) {
    return CACHED_STATEMENTS.computeIfAbsent(sql, SQLNormalizer::normalizeCharSequence);
  }

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"cassandra"};
  }

  @Override
  protected String service() {
    return SERVICE_NAME;
  }

  @Override
  protected CharSequence component() {
    return JAVA_CASSANDRA;
  }

  @Override
  protected CharSequence spanType() {
    return InternalSpanTypes.CASSANDRA;
  }

  @Override
  protected String dbType() {
    return DB_TYPE;
  }

  @Override
  protected String dbUser(final Session session) {
    return null;
  }

  @Override
  protected String dbInstance(final Session session) {
    return session.getLoggedKeyspace();
  }

  @Override
  protected String dbHostname(Session session) {
    // Getting hostname through session.getState() seems to be expensive
    return null;
  }

  public AgentSpan onStatement(final AgentSpan span, final CharSequence statement) {
    span.setResourceName(normalizedQuery(statement));
    return span;
  }

  public AgentSpan onResponse(final AgentSpan span, final ResultSet result) {
    if (result != null) {
      final Host host = result.getExecutionInfo().getQueriedHost();
      onPeerConnection(span, host.getSocketAddress());
      try {
        if (Config.get().isCassandraKeyspaceStatementExtractionEnabled()) {
          final ColumnDefinitions defs = result.getColumnDefinitions();
          if (defs != null && defs.size() > 0) {
            final String keySpace = defs.getKeyspace(0);
            if (Strings.isNotBlank(keySpace) && !keySpace.equals(span.getTag(DB_INSTANCE))) {
              onInstance(span, keySpace);
            }
          }
        }
      } catch (final Throwable ignored) {
      }
    }
    return span;
  }
}

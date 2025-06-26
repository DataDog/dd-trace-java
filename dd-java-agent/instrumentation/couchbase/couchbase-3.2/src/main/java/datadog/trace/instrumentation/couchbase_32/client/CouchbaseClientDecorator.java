package datadog.trace.instrumentation.couchbase_32.client;

import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import datadog.trace.api.naming.SpanNaming;
import datadog.trace.api.normalize.SQLNormalizer;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.DBTypeProcessingDatabaseClientDecorator;
import java.util.function.Function;
import java.util.function.ToIntFunction;

class CouchbaseClientDecorator extends DBTypeProcessingDatabaseClientDecorator {
  private static final String DB_TYPE = "couchbase";
  private static final String SERVICE_NAME =
      SpanNaming.instance().namingSchema().database().service(DB_TYPE);
  public static final CharSequence OPERATION_NAME =
      UTF8BytesString.create(SpanNaming.instance().namingSchema().database().operation(DB_TYPE));
  public static final CharSequence COUCHBASE_CLIENT = UTF8BytesString.create("couchbase-client");
  public static final CouchbaseClientDecorator DECORATE = new CouchbaseClientDecorator();

  private static final Function<String, UTF8BytesString> NORMALIZE = SQLNormalizer::normalize;
  private static final int COMBINED_STATEMENT_LIMIT = 2 * 1024 * 1024; // characters

  private static final ToIntFunction<UTF8BytesString> STATEMENT_WEIGHER = UTF8BytesString::length;
  private static final DDCache<String, UTF8BytesString> CACHED_STATEMENTS =
      DDCaches.newFixedSizeWeightedCache(512, STATEMENT_WEIGHER, COMBINED_STATEMENT_LIMIT);

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"couchbase"};
  }

  @Override
  protected String service() {
    return SERVICE_NAME;
  }

  @Override
  protected CharSequence component() {
    return COUCHBASE_CLIENT;
  }

  @Override
  protected CharSequence spanType() {
    return InternalSpanTypes.COUCHBASE;
  }

  @Override
  protected String dbType() {
    return DB_TYPE;
  }

  @Override
  protected String dbUser(final Object o) {
    return null;
  }

  @Override
  protected String dbInstance(final Object o) {
    return null;
  }

  @Override
  protected String dbHostname(Object o) {
    return null;
  }

  protected static UTF8BytesString normalizedQuery(String sql) {
    return CACHED_STATEMENTS.computeIfAbsent(sql, NORMALIZE);
  }
}

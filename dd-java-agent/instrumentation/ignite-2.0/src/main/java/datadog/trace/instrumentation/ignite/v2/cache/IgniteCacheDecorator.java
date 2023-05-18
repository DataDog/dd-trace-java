package datadog.trace.instrumentation.ignite.v2.cache;

import datadog.trace.api.Config;
import datadog.trace.api.naming.SpanNaming;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.DBTypeProcessingDatabaseClientDecorator;
import org.apache.ignite.Ignite;
import org.apache.ignite.IgniteCache;
import org.apache.ignite.cache.query.Query;
import org.apache.ignite.cache.query.SqlFieldsQuery;
import org.apache.ignite.cache.query.SqlQuery;

public class IgniteCacheDecorator extends DBTypeProcessingDatabaseClientDecorator<IgniteCache> {
  public static final IgniteCacheDecorator DECORATE = new IgniteCacheDecorator();
  public static final String DB_TYPE = "ignite";
  public static final CharSequence OPERATION_NAME =
      UTF8BytesString.create(SpanNaming.instance().namingSchema().cache().operation(DB_TYPE));
  private static final CharSequence SPAN_TYPE = InternalSpanTypes.CACHE;
  private static final CharSequence COMPONENT_NAME = UTF8BytesString.create("ignite-cache");

  private final boolean includeKeys;

  public IgniteCacheDecorator() {
    this(Config.get().isIgniteCacheIncludeKeys());
  }

  public IgniteCacheDecorator(boolean includeKeys) {
    this.includeKeys = includeKeys;
  }

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"ignite"};
  }

  @Override
  protected String service() {
    return SpanNaming.instance()
        .namingSchema()
        .cache()
        .service(Config.get().getServiceName(), DB_TYPE);
  }

  @Override
  protected CharSequence component() {
    return COMPONENT_NAME;
  }

  @Override
  protected CharSequence spanType() {
    return SPAN_TYPE;
  }

  @Override
  protected String dbType() {
    return DB_TYPE;
  }

  @Override
  protected String dbUser(IgniteCache igniteCache) {
    return null;
  }

  @Override
  protected String dbInstance(IgniteCache igniteCache) {
    return null;
  }

  @Override
  protected CharSequence dbHostname(IgniteCache igniteCache) {
    return null;
  }

  public AgentSpan onOperation(
      final AgentSpan span, final String cacheName, final String methodName) {
    return onOperation(span, cacheName, methodName, null);
  }

  public AgentSpan onQuery(
      final AgentSpan span, final String cacheName, final String methodName, final Query query) {
    if (methodName != null) {
      span.setTag("ignite.operation", "cache." + methodName);
    }

    if (cacheName != null) {
      span.setTag("ignite.cache.name", cacheName);
    }

    final String queryType = query.getClass().getSimpleName();
    span.setTag("ignite.cache.query_type", queryType);

    if (query instanceof SqlFieldsQuery) {
      IgniteQueryInfo queryInfo =
          IgniteQueryInfo.ofPreparedStatement(((SqlFieldsQuery) query).getSql());

      span.setResourceName(queryInfo.getSql());
      span.setTag(Tags.DB_OPERATION, queryInfo.getOperation());
    } else if (query instanceof SqlQuery) {
      SqlQuery<?, ?> sqlQuery = (SqlQuery) query;
      IgniteQueryInfo queryInfo =
          IgniteQueryInfo.ofPreparedStatement(sqlQuery.getSql(), sqlQuery.getType());

      span.setResourceName(queryInfo.getSql());
      span.setTag(Tags.DB_OPERATION, queryInfo.getOperation());
      span.setTag("ignite.cache.entity_type", sqlQuery.getType());
    } else {
      final StringBuilder resourceName = new StringBuilder("cache.");
      resourceName.append(methodName);
      resourceName.append(' ');
      resourceName.append(queryType);
      resourceName.append(" on ");
      resourceName.append(cacheName);
      span.setResourceName(resourceName);
    }

    return span;
  }

  public AgentSpan onOperation(
      final AgentSpan span, final String cacheName, final String methodName, Object key) {

    final StringBuilder resourceName = new StringBuilder("cache.");
    if (methodName != null && methodName.endsWith("Long")) {
      resourceName.append(methodName.substring(0, methodName.length() - "Long".length()));
    } else {
      resourceName.append(methodName);
    }

    span.setTag("ignite.operation", resourceName.toString());

    if (cacheName != null) {
      span.setTag("ignite.cache.name", cacheName);
      resourceName.append(" on ");
      resourceName.append(cacheName);
    }

    span.setResourceName(resourceName);

    if (includeKeys && key != null) {
      span.setTag("ignite.cache.key", key.toString());
    }

    return span;
  }

  public AgentSpan onIgnite(final AgentSpan span, final Ignite ignite) {

    if (ignite == null) {
      return span;
    }

    if (ignite.name() != null) {
      span.setTag("ignite.instance", ignite.name());
    }
    span.setTag("ignite.version", ignite.version().toString());

    return span;
  }

  public AgentSpan onResult(AgentSpan span, Object result) {
    return span;
  }
}

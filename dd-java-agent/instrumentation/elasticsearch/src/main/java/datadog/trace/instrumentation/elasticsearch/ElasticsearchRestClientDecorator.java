package datadog.trace.instrumentation.elasticsearch;

import static datadog.trace.api.Functions.PATH_BASED_RESOURCE_NAME;
import static datadog.trace.api.http.UrlBasedResourceNameCalculator.SIMPLE_PATH_NORMALIZER;
import static datadog.trace.bootstrap.instrumentation.api.Tags.DB_TYPE;

import datadog.trace.api.Pair;
import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.DatabaseClientDecorator;
import org.elasticsearch.client.Response;

public class ElasticsearchRestClientDecorator extends DatabaseClientDecorator {

  public static final CharSequence ELASTICSEARCH_REST_QUERY =
      UTF8BytesString.create("elasticsearch.rest.query");
  public static final CharSequence ELASTICSEARCH_JAVA =
      UTF8BytesString.create("elasticsearch-java");

  private static final DDCache<Pair<String, String>, UTF8BytesString> RESOURCE_NAMES =
      DDCaches.newFixedSizeCache(256);

  public static final ElasticsearchRestClientDecorator DECORATE =
      new ElasticsearchRestClientDecorator();

  @Override
  public AgentSpan afterStart(AgentSpan span) {
    span.setServiceName(dbType());
    span.setTag(DB_TYPE, dbType());
    return super.afterStart(span);
  }

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"elasticsearch"};
  }

  @Override
  protected String service() {
    return "elasticsearch";
  }

  @Override
  protected CharSequence component() {
    return ELASTICSEARCH_JAVA;
  }

  @Override
  protected CharSequence spanType() {
    return InternalSpanTypes.ELASTICSEARCH;
  }

  @Override
  protected String dbType() {
    return "elasticsearch";
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

  public AgentSpan onRequest(final AgentSpan span, final String method, final String endpoint) {
    span.setTag(Tags.HTTP_METHOD, method);
    span.setTag(Tags.HTTP_URL, endpoint);
    span.setResourceName(
        RESOURCE_NAMES.computeIfAbsent(
            Pair.of(method, SIMPLE_PATH_NORMALIZER.normalize(endpoint)), PATH_BASED_RESOURCE_NAME));
    return span;
  }

  public AgentSpan onResponse(final AgentSpan span, final Response response) {
    if (response != null && response.getHost() != null) {
      span.setTag(Tags.PEER_HOSTNAME, response.getHost().getHostName());
      setPeerPort(span, response.getHost().getPort());
    }
    return span;
  }
}

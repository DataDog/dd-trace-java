package datadog.trace.instrumentation.elasticsearch;

import static datadog.trace.bootstrap.instrumentation.decorator.http.HttpResourceDecorator.HTTP_RESOURCE_DECORATOR;

import datadog.trace.api.Config;
import datadog.trace.api.naming.SpanNaming;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.DBTypeProcessingDatabaseClientDecorator;
import org.elasticsearch.client.Response;

public class ElasticsearchRestClientDecorator extends DBTypeProcessingDatabaseClientDecorator {

  private static final String SERVICE_NAME =
      SpanNaming.instance()
          .namingSchema()
          .database()
          .service(Config.get().getServiceName(), "elasticsearch");

  public static final CharSequence OPERATION_NAME =
      UTF8BytesString.create(
          SpanNaming.instance().namingSchema().database().operation("elasticsearch.rest"));
  public static final CharSequence ELASTICSEARCH_JAVA =
      UTF8BytesString.create("elasticsearch-java");

  public static final ElasticsearchRestClientDecorator DECORATE =
      new ElasticsearchRestClientDecorator();

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"elasticsearch"};
  }

  @Override
  protected String service() {
    return SERVICE_NAME;
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
    return HTTP_RESOURCE_DECORATOR.withClientPath(span, method, endpoint);
  }

  public AgentSpan onResponse(final AgentSpan span, final Response response) {
    if (response != null && response.getHost() != null) {
      span.setTag(Tags.PEER_HOSTNAME, response.getHost().getHostName());
      setPeerPort(span, response.getHost().getPort());
    }
    return span;
  }
}

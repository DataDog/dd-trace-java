package datadog.trace.instrumentation.opensearch;

import static datadog.trace.bootstrap.instrumentation.decorator.http.HttpResourceDecorator.HTTP_RESOURCE_DECORATOR;

import datadog.trace.api.Config;
import datadog.trace.api.naming.SpanNaming;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.DBTypeProcessingDatabaseClientDecorator;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.apache.http.HttpEntity;
import org.opensearch.client.Response;

public class OpensearchRestClientDecorator extends DBTypeProcessingDatabaseClientDecorator {

  private static final int MAX_OPENSEARCH_BODY_CONTENT_LENGTH = 25000;
  private static final String SERVICE_NAME =
      SpanNaming.instance().namingSchema().database().service("opensearch");

  public static final CharSequence OPERATION_NAME =
      UTF8BytesString.create(
          SpanNaming.instance().namingSchema().database().operation("opensearch.rest"));
  public static final CharSequence OPENSEARCH_JAVA = UTF8BytesString.create("opensearch-java");

  public static final OpensearchRestClientDecorator DECORATE = new OpensearchRestClientDecorator();

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"opensearch"};
  }

  @Override
  protected String service() {
    return SERVICE_NAME;
  }

  @Override
  protected CharSequence component() {
    return OPENSEARCH_JAVA;
  }

  @Override
  protected CharSequence spanType() {
    return InternalSpanTypes.OPENSEARCH;
  }

  @Override
  protected String dbType() {
    return "opensearch";
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

  private String getOpensearchRequestBody(HttpEntity entity) {
    try (BufferedReader bodyBufferedReader =
        new BufferedReader(new InputStreamReader(entity.getContent(), StandardCharsets.UTF_8))) {
      StringBuilder bodyStringBuilder = new StringBuilder();
      String bodyline;
      while ((bodyline = bodyBufferedReader.readLine()) != null) {
        bodyStringBuilder.append(bodyline);
      }
      return bodyStringBuilder.toString();
    } catch (IOException e) {
      return "";
    }
  }

  public AgentSpan onRequest(
      final AgentSpan span,
      final String method,
      final String endpoint,
      final HttpEntity entity,
      final Map<String, String> parameters) {
    span.setTag(Tags.HTTP_METHOD, method);
    span.setTag(Tags.HTTP_URL, endpoint);

    final Config config = Config.get();
    // Elasticsearch also controls OpenSearch
    if (config.isElasticsearchBodyEnabled() || config.isElasticsearchBodyAndParamsEnabled()) {
      if (entity != null) {
        long contentLength = entity.getContentLength();
        if (contentLength <= MAX_OPENSEARCH_BODY_CONTENT_LENGTH) {
          span.setTag("opensearch.body", getOpensearchRequestBody(entity));
        } else {
          span.setTag(
              "opensearch.body",
              "<body size "
                  + contentLength
                  + " exceeds limit of "
                  + MAX_OPENSEARCH_BODY_CONTENT_LENGTH
                  + ">");
        }
      }
    }

    if (config.isElasticsearchParamsEnabled() || config.isElasticsearchBodyAndParamsEnabled()) {
      if (parameters != null) {
        StringBuilder queryParametersStringBuilder = new StringBuilder();
        for (Map.Entry<String, String> parameter : parameters.entrySet()) {
          queryParametersStringBuilder.append(
              parameter.getKey() + "=" + parameter.getValue() + "&");
        }
        if (queryParametersStringBuilder.length() >= 1) {
          queryParametersStringBuilder.deleteCharAt(queryParametersStringBuilder.length() - 1);
        }
        span.setTag("opensearch.params", queryParametersStringBuilder.toString());
      }
    }
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

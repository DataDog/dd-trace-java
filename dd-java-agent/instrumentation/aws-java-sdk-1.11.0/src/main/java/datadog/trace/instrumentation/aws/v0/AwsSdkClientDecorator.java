package datadog.trace.instrumentation.aws.v0;

import com.amazonaws.AmazonWebServiceRequest;
import com.amazonaws.AmazonWebServiceResponse;
import com.amazonaws.Request;
import com.amazonaws.Response;
import datadog.trace.api.Function;
import datadog.trace.api.Functions;
import datadog.trace.api.cache.QualifiedClassNameCache;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.HttpClientDecorator;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.net.URI;
import java.util.Map;

public class AwsSdkClientDecorator extends HttpClientDecorator<Request, Response> {

  static final CharSequence COMPONENT_NAME = UTF8BytesString.create("java-aws-sdk");

  @SuppressForbidden
  private final QualifiedClassNameCache cache =
      new QualifiedClassNameCache(
          new Function<Class<?>, CharSequence>() {
            @Override
            public String apply(Class<?> input) {
              return input.getSimpleName().replace("Request", "");
            }
          },
          Functions.SuffixJoin.of(
              ".",
              new Function<CharSequence, CharSequence>() {
                @Override
                public CharSequence apply(CharSequence serviceName) {
                  return String.valueOf(serviceName).replace("Amazon", "").trim();
                }
              }));

  private final ContextStore<AmazonWebServiceRequest, Map> contextStore;

  public AwsSdkClientDecorator(final ContextStore<AmazonWebServiceRequest, Map> contextStore) {
    this.contextStore = contextStore;
  }

  @Override
  public AgentSpan onRequest(final AgentSpan span, final Request request) {
    // Call super first because we override the resource name below.
    super.onRequest(span, request);

    final String awsServiceName = request.getServiceName();
    final AmazonWebServiceRequest originalRequest = request.getOriginalRequest();
    final Class<?> awsOperation = originalRequest.getClass();

    span.setTag("aws.agent", COMPONENT_NAME);
    span.setTag("aws.service", awsServiceName);
    span.setTag("aws.operation", awsOperation.getSimpleName());
    span.setTag("aws.endpoint", request.getEndpoint().toString());

    span.setResourceName(cache.getQualifiedName(awsOperation, awsServiceName));
    span.setMeasured(true);

    if (contextStore != null) {
      final Map<String, String> requestMeta = contextStore.get(originalRequest);
      if (requestMeta != null) {
        span.setTag("aws.bucket.name", requestMeta.get("aws.bucket.name"));
        span.setTag("aws.queue.url", requestMeta.get("aws.queue.url"));
        span.setTag("aws.queue.name", requestMeta.get("aws.queue.name"));
        span.setTag("aws.stream.name", requestMeta.get("aws.stream.name"));
        span.setTag("aws.table.name", requestMeta.get("aws.table.name"));
      }
    }

    return span;
  }

  @Override
  public AgentSpan onResponse(final AgentSpan span, final Response response) {
    if (response.getAwsResponse() instanceof AmazonWebServiceResponse) {
      final AmazonWebServiceResponse awsResp = (AmazonWebServiceResponse) response.getAwsResponse();
      span.setTag("aws.requestId", awsResp.getRequestId());
    }
    return super.onResponse(span, response);
  }

  @Override
  protected String service() {
    return COMPONENT_NAME.toString();
  }

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"aws-sdk"};
  }

  @Override
  protected CharSequence component() {
    return COMPONENT_NAME;
  }

  @Override
  protected String method(final Request request) {
    return request.getHttpMethod().name();
  }

  @Override
  protected URI url(final Request request) {
    return request.getEndpoint();
  }

  @Override
  protected int status(final Response response) {
    return response.getHttpResponse().getStatusCode();
  }
}

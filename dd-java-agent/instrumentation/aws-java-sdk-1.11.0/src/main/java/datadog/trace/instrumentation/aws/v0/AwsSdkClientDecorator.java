package datadog.trace.instrumentation.aws.v0;

import com.amazonaws.AmazonWebServiceRequest;
import com.amazonaws.AmazonWebServiceResponse;
import com.amazonaws.Request;
import com.amazonaws.Response;
import datadog.trace.api.Functions;
import datadog.trace.api.cache.QualifiedClassNameCache;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.HttpClientDecorator;
import java.net.URI;
import java.util.function.Function;
import java.util.regex.Pattern;

public class AwsSdkClientDecorator extends HttpClientDecorator<Request, Response> {
  public static final AwsSdkClientDecorator DECORATE = new AwsSdkClientDecorator();

  private static final Pattern REQUEST_PATTERN = Pattern.compile("Request", Pattern.LITERAL);
  private static final Pattern AMAZON_PATTERN = Pattern.compile("Amazon", Pattern.LITERAL);

  static final CharSequence COMPONENT_NAME = UTF8BytesString.create("java-aws-sdk");

  private final QualifiedClassNameCache cache =
      new QualifiedClassNameCache(
          new Function<Class<?>, CharSequence>() {
            @Override
            public String apply(Class<?> input) {
              return REQUEST_PATTERN.matcher(input.getSimpleName()).replaceAll("");
            }
          },
          Functions.SuffixJoin.of(
              ".",
              new Function<CharSequence, CharSequence>() {
                @Override
                public CharSequence apply(CharSequence serviceName) {
                  return AMAZON_PATTERN.matcher(String.valueOf(serviceName)).replaceAll("").trim();
                }
              }));

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

    CharSequence awsRequestName = cache.getQualifiedName(awsOperation, awsServiceName);

    span.setResourceName(awsRequestName);

    switch (awsRequestName.toString()) {
      case "SQS.SendMessage":
      case "SQS.SendMessageBatch":
      case "SQS.ReceiveMessage":
      case "SQS.DeleteMessage":
        span.setServiceName("sqs");
        break;
      case "SNS.Publish":
        span.setServiceName("sns");
        break;
      default:
        span.setServiceName("java-aws-sdk");
        break;
    }

    RequestAccess access = RequestAccess.of(originalRequest);
    String bucketName = access.getBucketName(originalRequest);
    if (null != bucketName) {
      span.setTag("aws.bucket.name", bucketName);
    }
    String queueUrl = access.getQueueUrl(originalRequest);
    if (null != queueUrl) {
      span.setTag("aws.queue.url", queueUrl);
    }
    String queueName = access.getQueueName(originalRequest);
    if (null != queueName) {
      span.setTag("aws.queue.name", queueName);
    }
    String topicArn = access.getTopicArn(originalRequest);
    if (null != topicArn) {
      span.setTag("aws.topic.name", topicArn.substring(topicArn.lastIndexOf(':') + 1));
    }
    String streamName = access.getStreamName(originalRequest);
    if (null != streamName) {
      span.setTag("aws.stream.name", streamName);
    }
    String tableName = access.getTableName(originalRequest);
    if (null != tableName) {
      span.setTag("aws.table.name", tableName);
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
  protected boolean shouldSetResourceName() {
    return false;
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

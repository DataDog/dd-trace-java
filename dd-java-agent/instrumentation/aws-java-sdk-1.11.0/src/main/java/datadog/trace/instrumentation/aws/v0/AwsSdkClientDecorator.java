package datadog.trace.instrumentation.aws.v0;

import static datadog.trace.bootstrap.instrumentation.api.ResourceNamePriorities.RPC_COMMAND_NAME;

import com.amazonaws.AmazonWebServiceRequest;
import com.amazonaws.AmazonWebServiceResponse;
import com.amazonaws.Request;
import com.amazonaws.Response;
import datadog.trace.api.Config;
import datadog.trace.api.DDTags;
import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import datadog.trace.api.naming.SpanNaming;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.HttpClientDecorator;
import java.net.URI;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AwsSdkClientDecorator extends HttpClientDecorator<Request, Response>
    implements AgentPropagation.Setter<Request<?>> {

  private static final String AWS = "aws";

  static final CharSequence COMPONENT_NAME = UTF8BytesString.create("java-aws-sdk");

  public static final boolean AWS_LEGACY_TRACING =
      Config.get().isLegacyTracingEnabled(false, "aws-sdk");

  public static final boolean SQS_LEGACY_TRACING = Config.get().isLegacyTracingEnabled(true, "sqs");

  private static final String SQS_SERVICE_NAME =
      AWS_LEGACY_TRACING || SQS_LEGACY_TRACING ? "sqs" : Config.get().getServiceName();

  private static final String SNS_SERVICE_NAME =
      SpanNaming.instance().namingSchema().cloud().serviceForRequest(AWS, "sns");
  private static final String GENERIC_SERVICE_NAME =
      SpanNaming.instance().namingSchema().cloud().serviceForRequest(AWS, null);
  public static final AwsSdkClientDecorator DECORATE = new AwsSdkClientDecorator();

  private static final DDCache<String, String> serviceNameCache = DDCaches.newFixedSizeCache(128);
  private static final Pattern AWS_SERVICE_NAME_PATTERN = Pattern.compile("Amazon\\s?(\\w+)");

  private static String simplifyServiceName(String awsServiceName) {
    return serviceNameCache.computeIfAbsent(
        awsServiceName, AwsSdkClientDecorator::applyServiceNamePattern);
  }

  private static String applyServiceNamePattern(String awsServiceName) {
    if (awsServiceName != null) {
      Matcher matcher = AWS_SERVICE_NAME_PATTERN.matcher(awsServiceName);
      if (matcher.find()) {
        return matcher.group(1).toLowerCase(Locale.ROOT);
      }
    }
    return awsServiceName;
  }

  @Override
  public AgentSpan onRequest(final AgentSpan span, final Request request) {
    // Call super first because we override the resource name below.
    super.onRequest(span, request);

    final String awsServiceName = request.getServiceName();
    final AmazonWebServiceRequest originalRequest = request.getOriginalRequest();
    final Class<?> awsOperation = originalRequest.getClass();

    span.setTag(InstrumentationTags.AWS_AGENT, COMPONENT_NAME);
    span.setTag(InstrumentationTags.AWS_SERVICE, awsServiceName);
    span.setTag(InstrumentationTags.TOP_LEVEL_AWS_SERVICE, simplifyServiceName(awsServiceName));
    span.setTag(InstrumentationTags.AWS_OPERATION, awsOperation.getSimpleName());
    span.setTag(InstrumentationTags.AWS_ENDPOINT, request.getEndpoint().toString());

    CharSequence awsRequestName = AwsNameCache.getQualifiedName(request);

    span.setResourceName(awsRequestName, RPC_COMMAND_NAME);

    switch (awsRequestName.toString()) {
      case "SQS.SendMessage":
      case "SQS.SendMessageBatch":
      case "SQS.ReceiveMessage":
      case "SQS.DeleteMessage":
      case "SQS.DeleteMessageBatch":
        span.setServiceName(SQS_SERVICE_NAME);
        break;
      case "SNS.Publish":
        span.setServiceName(SNS_SERVICE_NAME);
        break;
      default:
        span.setServiceName(GENERIC_SERVICE_NAME);
        break;
    }

    RequestAccess access = RequestAccess.of(originalRequest);
    String bucketName = access.getBucketName(originalRequest);
    String bestPrecursor = null;
    String bestPeerService = null;
    if (null != bucketName) {
      span.setTag(InstrumentationTags.AWS_BUCKET_NAME, bucketName);
      span.setTag(InstrumentationTags.BUCKET_NAME, bucketName);
      bestPrecursor = InstrumentationTags.AWS_BUCKET_NAME;
      bestPeerService = bucketName;
    }
    String queueUrl = access.getQueueUrl(originalRequest);
    if (null != queueUrl) {
      span.setTag(InstrumentationTags.AWS_QUEUE_URL, queueUrl);
      bestPrecursor = InstrumentationTags.AWS_QUEUE_URL;
      bestPeerService = queueUrl;
    }
    String queueName = access.getQueueName(originalRequest);
    if (null != queueName) {
      span.setTag(InstrumentationTags.AWS_QUEUE_NAME, queueName);
      span.setTag(InstrumentationTags.QUEUE_NAME, queueName);
      bestPrecursor = InstrumentationTags.AWS_QUEUE_NAME;
      bestPeerService = queueName;
    }
    String topicArn = access.getTopicArn(originalRequest);
    if (null != topicArn) {
      final String topicName = topicArn.substring(topicArn.lastIndexOf(':') + 1);
      span.setTag(InstrumentationTags.AWS_TOPIC_NAME, topicName);
      span.setTag(InstrumentationTags.TOPIC_NAME, topicName);
      bestPrecursor = InstrumentationTags.AWS_TOPIC_NAME;
      bestPeerService = topicName;
    }
    String streamName = access.getStreamName(originalRequest);
    if (null != streamName) {
      span.setTag(InstrumentationTags.AWS_STREAM_NAME, streamName);
      span.setTag(InstrumentationTags.STREAM_NAME, streamName);
      bestPrecursor = InstrumentationTags.AWS_STREAM_NAME;
      bestPeerService = streamName;
    }
    String tableName = access.getTableName(originalRequest);
    if (null != tableName) {
      span.setTag(InstrumentationTags.AWS_TABLE_NAME, tableName);
      span.setTag(InstrumentationTags.TABLE_NAME, tableName);
      bestPrecursor = InstrumentationTags.AWS_TABLE_NAME;
      bestPeerService = tableName;
    }

    // for aws we can calculate this eagerly without needing to have to looking up tags in the peer
    // service interceptor
    if (bestPrecursor != null && SpanNaming.instance().namingSchema().peerService().supports()) {
      span.setTag(Tags.PEER_SERVICE, bestPeerService);
      span.setTag(DDTags.PEER_SERVICE_SOURCE, bestPrecursor);
    }

    return span;
  }

  @Override
  public AgentSpan onResponse(final AgentSpan span, final Response response) {
    if (response.getAwsResponse() instanceof AmazonWebServiceResponse) {
      final AmazonWebServiceResponse awsResp = (AmazonWebServiceResponse) response.getAwsResponse();
      span.setTag(InstrumentationTags.AWS_REQUEST_ID, awsResp.getRequestId());
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

  @Override
  public void set(Request<?> carrier, String key, String value) {
    carrier.addHeader(key, value);
  }

  @Override
  protected String getRequestHeader(Request request, String headerName) {
    Object header = request.getHeaders().get(headerName);
    if (null != header) {
      return header.toString();
    }
    return null;
  }

  @Override
  protected String getResponseHeader(Response response, String headerName) {
    return response.getHttpResponse().getHeaders().get(headerName);
  }
}

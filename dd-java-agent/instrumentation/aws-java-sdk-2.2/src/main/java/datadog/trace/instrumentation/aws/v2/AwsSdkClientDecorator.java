package datadog.trace.instrumentation.aws.v2;

import datadog.trace.api.Config;
import datadog.trace.api.DDTags;
import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import datadog.trace.api.naming.SpanNaming;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags;
import datadog.trace.bootstrap.instrumentation.api.ResourceNamePriorities;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.HttpClientDecorator;
import java.net.URI;
import javax.annotation.Nonnull;
import software.amazon.awssdk.awscore.AwsResponse;
import software.amazon.awssdk.core.SdkRequest;
import software.amazon.awssdk.core.SdkResponse;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.SdkExecutionAttribute;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.http.SdkHttpResponse;

public class AwsSdkClientDecorator extends HttpClientDecorator<SdkHttpRequest, SdkHttpResponse>
    implements AgentPropagation.Setter<SdkHttpRequest.Builder> {
  public static final AwsSdkClientDecorator DECORATE = new AwsSdkClientDecorator();
  private static final DDCache<String, CharSequence> CACHE =
      DDCaches.newFixedSizeCache(128); // cloud services can have high cardinality

  static final CharSequence COMPONENT_NAME = UTF8BytesString.create("java-aws-sdk");

  // We only want tag interceptor to take priority
  private static final byte RESOURCE_NAME_PRIORITY = ResourceNamePriorities.TAG_INTERCEPTOR - 1;

  public static final boolean AWS_LEGACY_TRACING =
      Config.get().isLegacyTracingEnabled(false, "aws-sdk");

  public static final boolean SQS_LEGACY_TRACING = Config.get().isLegacyTracingEnabled(true, "sqs");

  private static final String SQS_SERVICE_NAME =
      AWS_LEGACY_TRACING || SQS_LEGACY_TRACING ? "sqs" : Config.get().getServiceName();

  private static final String SNS_SERVICE_NAME =
      SpanNaming.instance().namingSchema().cloud().serviceForRequest("aws", "sns");
  private static final String GENERIC_SERVICE_NAME =
      SpanNaming.instance().namingSchema().cloud().serviceForRequest("aws", null);

  public CharSequence spanName(final ExecutionAttributes attributes) {
    final String awsServiceName = attributes.getAttribute(SdkExecutionAttribute.SERVICE_NAME);
    final String awsOperationName = attributes.getAttribute(SdkExecutionAttribute.OPERATION_NAME);

    final String qualifiedName = awsServiceName + "." + awsOperationName;

    return CACHE.computeIfAbsent(
        qualifiedName,
        s ->
            SpanNaming.instance()
                .namingSchema()
                .cloud()
                .operationForRequest(
                    "aws", attributes.getAttribute(SdkExecutionAttribute.SERVICE_NAME), s));
  }

  public AgentSpan onSdkRequest(final AgentSpan span, final SdkRequest request) {
    // S3
    request.getValueForField("Bucket", String.class).ifPresent(name -> setBucketName(span, name));
    request
        .getValueForField("StorageClass", String.class)
        .ifPresent(
            storageClass -> span.setTag(InstrumentationTags.AWS_STORAGE_CLASS, storageClass));

    // SQS
    request
        .getValueForField("QueueUrl", String.class)
        .ifPresent(
            url -> {
              span.setTag(InstrumentationTags.AWS_QUEUE_URL, url);
              setPeerService(span, InstrumentationTags.AWS_QUEUE_URL, url);
            });
    request.getValueForField("QueueName", String.class).ifPresent(name -> setQueueName(span, name));

    // SNS
    request
        .getValueForField("TopicArn", String.class)
        .ifPresent(arn -> setTopicName(span, arn.substring(arn.lastIndexOf(':') + 1)));

    // Kinesis
    request
        .getValueForField("StreamName", String.class)
        .ifPresent(name -> setStreamName(span, name));

    // DynamoDB
    request.getValueForField("TableName", String.class).ifPresent(name -> setTableName(span, name));

    return span;
  }

  private static void setPeerService(
      @Nonnull final AgentSpan span, @Nonnull final String precursor, @Nonnull final String value) {
    if (SpanNaming.instance().namingSchema().peerService().supports()) {
      span.setTag(Tags.PEER_SERVICE, value);
      span.setTag(DDTags.PEER_SERVICE_SOURCE, precursor);
    }
  }

  private static void setBucketName(AgentSpan span, String name) {
    span.setTag(InstrumentationTags.AWS_BUCKET_NAME, name);
    span.setTag(InstrumentationTags.BUCKET_NAME, name);
    setPeerService(span, InstrumentationTags.AWS_BUCKET_NAME, name);
  }

  private static void setQueueName(AgentSpan span, String name) {
    span.setTag(InstrumentationTags.AWS_QUEUE_NAME, name);
    span.setTag(InstrumentationTags.QUEUE_NAME, name);
    setPeerService(span, InstrumentationTags.AWS_QUEUE_NAME, name);
  }

  private static void setTopicName(AgentSpan span, String name) {
    span.setTag(InstrumentationTags.AWS_TOPIC_NAME, name);
    span.setTag(InstrumentationTags.TOPIC_NAME, name);
    setPeerService(span, InstrumentationTags.AWS_TOPIC_NAME, name);
  }

  private static void setStreamName(AgentSpan span, String name) {
    span.setTag(InstrumentationTags.AWS_STREAM_NAME, name);
    span.setTag(InstrumentationTags.STREAM_NAME, name);
    setPeerService(span, InstrumentationTags.AWS_STREAM_NAME, name);
  }

  private static void setTableName(AgentSpan span, String name) {
    span.setTag(InstrumentationTags.AWS_TABLE_NAME, name);
    span.setTag(InstrumentationTags.TABLE_NAME, name);
    setPeerService(span, InstrumentationTags.AWS_TABLE_NAME, name);
  }

  public AgentSpan onAttributes(final AgentSpan span, final ExecutionAttributes attributes) {
    final String awsServiceName = attributes.getAttribute(SdkExecutionAttribute.SERVICE_NAME);
    final String awsOperationName = attributes.getAttribute(SdkExecutionAttribute.OPERATION_NAME);

    String awsRequestName = awsServiceName + "." + awsOperationName;
    span.setResourceName(awsRequestName, RESOURCE_NAME_PRIORITY);

    switch (awsRequestName) {
      case "Sqs.SendMessage":
      case "Sqs.SendMessageBatch":
      case "Sqs.ReceiveMessage":
      case "Sqs.DeleteMessage":
      case "Sqs.DeleteMessageBatch":
        span.setServiceName(SQS_SERVICE_NAME);
        break;
      case "Sns.PublishBatch":
      case "Sns.Publish":
        span.setServiceName(SNS_SERVICE_NAME);
        break;
      default:
        span.setServiceName(GENERIC_SERVICE_NAME);
        break;
    }
    span.setTag(InstrumentationTags.AWS_AGENT, COMPONENT_NAME);
    span.setTag(InstrumentationTags.AWS_SERVICE, awsServiceName);
    span.setTag(InstrumentationTags.TOP_LEVEL_AWS_SERVICE, awsServiceName);
    span.setTag(InstrumentationTags.AWS_OPERATION, awsOperationName);

    return span;
  }

  // Not overriding the super.  Should call both with each type of response.
  public AgentSpan onResponse(final AgentSpan span, final SdkResponse response) {
    if (response instanceof AwsResponse) {
      span.setTag(
          InstrumentationTags.AWS_REQUEST_ID,
          ((AwsResponse) response).responseMetadata().requestId());
    }
    return span;
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
  protected String method(final SdkHttpRequest request) {
    return request.method().name();
  }

  @Override
  protected URI url(final SdkHttpRequest request) {
    return request.getUri();
  }

  @Override
  protected int status(final SdkHttpResponse response) {
    return response.statusCode();
  }

  @Override
  public void set(SdkHttpRequest.Builder carrier, String key, String value) {
    carrier.putHeader(key, value);
  }

  @Override
  protected String getRequestHeader(SdkHttpRequest request, String headerName) {
    return request.firstMatchingHeader(headerName).orElse(null);
  }

  @Override
  protected String getResponseHeader(SdkHttpResponse response, String headerName) {
    return response.firstMatchingHeader(headerName).orElse(null);
  }
}

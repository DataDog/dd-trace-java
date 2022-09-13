package datadog.trace.instrumentation.aws.v2;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.ResourceNamePriorities;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.HttpClientDecorator;
import java.net.URI;
import software.amazon.awssdk.awscore.AwsResponse;
import software.amazon.awssdk.core.SdkRequest;
import software.amazon.awssdk.core.SdkResponse;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.SdkExecutionAttribute;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.http.SdkHttpResponse;

public class AwsSdkClientDecorator extends HttpClientDecorator<SdkHttpRequest, SdkHttpResponse> {
  public static final AwsSdkClientDecorator DECORATE = new AwsSdkClientDecorator();

  public static final CharSequence AWS_HTTP = UTF8BytesString.create("aws.http");

  static final CharSequence COMPONENT_NAME = UTF8BytesString.create("java-aws-sdk");

  // We only want tag interceptor to take priority
  private static final byte RESOURCE_NAME_PRIORITY = ResourceNamePriorities.TAG_INTERCEPTOR - 1;

  public AgentSpan onSdkRequest(final AgentSpan span, final SdkRequest request) {
    // S3
    request
        .getValueForField("Bucket", String.class)
        .ifPresent(name -> span.setTag("aws.bucket.name", name));
    request
        .getValueForField("StorageClass", String.class)
        .ifPresent(storageClass -> span.setTag("aws.storage.class", storageClass));
    // SQS
    request
        .getValueForField("QueueUrl", String.class)
        .ifPresent(name -> span.setTag("aws.queue.url", name));
    request
        .getValueForField("QueueName", String.class)
        .ifPresent(name -> span.setTag("aws.queue.name", name));
    // SNS
    request
        .getValueForField("TopicArn", String.class)
        .ifPresent(
            name -> span.setTag("aws.topic.name", name.substring(name.lastIndexOf(':') + 1)));
    // Kinesis
    request
        .getValueForField("StreamName", String.class)
        .ifPresent(name -> span.setTag("aws.stream.name", name));
    // DynamoDB
    request
        .getValueForField("TableName", String.class)
        .ifPresent(name -> span.setTag("aws.table.name", name));
    return span;
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
        span.setServiceName("sqs");
        break;
      case "Sns.Publish":
        span.setServiceName("sns");
        break;
      default:
        span.setServiceName("java-aws-sdk");
        break;
    }

    span.setTag("aws.agent", COMPONENT_NAME);
    span.setTag("aws.service", awsServiceName);
    span.setTag("aws.operation", awsOperationName);

    return span;
  }

  // Not overriding the super.  Should call both with each type of response.
  public AgentSpan onResponse(final AgentSpan span, final SdkResponse response) {
    if (response instanceof AwsResponse) {
      span.setTag("aws.requestId", ((AwsResponse) response).responseMetadata().requestId());
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
}

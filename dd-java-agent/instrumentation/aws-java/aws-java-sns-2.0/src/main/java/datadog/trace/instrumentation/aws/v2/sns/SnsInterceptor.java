package datadog.trace.instrumentation.aws.v2.sns;

import static datadog.context.propagation.Propagators.defaultPropagator;
import static datadog.trace.api.datastreams.DataStreamsTags.Direction.OUTBOUND;
import static datadog.trace.api.datastreams.DataStreamsTags.create;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.traceConfig;
import static datadog.trace.instrumentation.aws.v2.sns.TextMapInjectAdapter.SETTER;

import datadog.context.Context;
import datadog.trace.api.Config;
import datadog.trace.api.datastreams.DataStreamsContext;
import datadog.trace.api.datastreams.DataStreamsTags;
import datadog.trace.bootstrap.InstanceStore;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.SdkRequest;
import software.amazon.awssdk.core.interceptor.Context.ModifyRequest;
import software.amazon.awssdk.core.interceptor.ExecutionAttribute;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.services.sns.model.MessageAttributeValue;
import software.amazon.awssdk.services.sns.model.PublishBatchRequest;
import software.amazon.awssdk.services.sns.model.PublishBatchRequestEntry;
import software.amazon.awssdk.services.sns.model.PublishRequest;

public class SnsInterceptor implements ExecutionInterceptor {

  public static final ExecutionAttribute<Context> CONTEXT_ATTRIBUTE =
      InstanceStore.of(ExecutionAttribute.class)
          .putIfAbsent("DatadogContext", () -> new ExecutionAttribute<>("DatadogContext"));

  private SdkBytes getMessageAttributeValueToInject(
      ExecutionAttributes executionAttributes, String snsTopicName) {
    Context context = executionAttributes.getAttribute(CONTEXT_ATTRIBUTE);
    StringBuilder jsonBuilder = new StringBuilder();
    jsonBuilder.append('{');
    if (traceConfig().isDataStreamsEnabled()) {
      DataStreamsContext dsmContext = DataStreamsContext.fromTags(getTags(snsTopicName));
      context = context.with(dsmContext);
    }
    defaultPropagator().inject(context, jsonBuilder, SETTER);
    jsonBuilder.setLength(jsonBuilder.length() - 1); // Remove the last comma
    jsonBuilder.append('}');
    return SdkBytes.fromString(jsonBuilder.toString(), StandardCharsets.UTF_8);
  }

  public SnsInterceptor() {}

  @Override
  public SdkRequest modifyRequest(ModifyRequest context, ExecutionAttributes executionAttributes) {
    // Injecting the trace context into SNS messageAttributes.
    if (context.request() instanceof PublishRequest) {
      PublishRequest request = (PublishRequest) context.request();
      // 10 messageAttributes is a limit from SQS, which is often used as a subscriber, therefore
      // the limit still applies here
      if (request.messageAttributes().size() < 10
          && Config.get().isAwsInjectDatadogAttributeEnabled()) {
        // Get topic name for DSM
        String snsTopicArn = request.topicArn();
        if (null == snsTopicArn) {
          snsTopicArn = request.targetArn();
          if (null == snsTopicArn) {
            return request; // request is to phone number, ignore for DSM
          }
        }

        String snsTopicName = snsTopicArn.substring(snsTopicArn.lastIndexOf(':') + 1);
        Map<String, MessageAttributeValue> modifiedMessageAttributes =
            new HashMap<>(request.messageAttributes());
        modifiedMessageAttributes.put(
            "_datadog", // Use Binary since SNS subscription filter policies fail silently with JSON
            // strings https://github.com/DataDog/datadog-lambda-js/pull/269
            MessageAttributeValue.builder()
                .dataType("Binary")
                .binaryValue(
                    this.getMessageAttributeValueToInject(executionAttributes, snsTopicName))
                .build());
        return request.toBuilder().messageAttributes(modifiedMessageAttributes).build();
      }
      return request;
    } else if (context.request() instanceof PublishBatchRequest) {
      PublishBatchRequest request = (PublishBatchRequest) context.request();
      // Get topic name for DSM
      String snsTopicArn = request.topicArn();
      String snsTopicName = snsTopicArn.substring(snsTopicArn.lastIndexOf(':') + 1);
      ArrayList<PublishBatchRequestEntry> entries = new ArrayList<>();
      final SdkBytes sdkBytes =
          this.getMessageAttributeValueToInject(executionAttributes, snsTopicName);
      for (PublishBatchRequestEntry entry : request.publishBatchRequestEntries()) {
        if (entry.messageAttributes().size() < 10) {
          Map<String, MessageAttributeValue> modifiedMessageAttributes =
              new HashMap<>(entry.messageAttributes());
          modifiedMessageAttributes.put(
              "_datadog",
              MessageAttributeValue.builder().dataType("Binary").binaryValue(sdkBytes).build());
          entries.add(entry.toBuilder().messageAttributes(modifiedMessageAttributes).build());
        }
      }
      return request.toBuilder().publishBatchRequestEntries(entries).build();
    }
    return context.request();
  }

  private DataStreamsTags getTags(String snsTopicName) {
    return create("sns", OUTBOUND, snsTopicName);
  }
}

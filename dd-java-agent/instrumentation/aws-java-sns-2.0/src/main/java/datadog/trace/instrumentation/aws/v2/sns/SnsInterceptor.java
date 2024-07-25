package datadog.trace.instrumentation.aws.v2.sns;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.propagate;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.traceConfig;
import static datadog.trace.core.datastreams.TagsProcessor.DIRECTION_OUT;
import static datadog.trace.core.datastreams.TagsProcessor.DIRECTION_TAG;
import static datadog.trace.core.datastreams.TagsProcessor.TOPIC_TAG;
import static datadog.trace.core.datastreams.TagsProcessor.TYPE_TAG;
import static datadog.trace.instrumentation.aws.v2.sns.TextMapInjectAdapter.SETTER;

import datadog.trace.api.TracePropagationStyle;
import datadog.trace.bootstrap.InstanceStore;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.SdkRequest;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttribute;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.services.sns.model.MessageAttributeValue;
import software.amazon.awssdk.services.sns.model.PublishBatchRequest;
import software.amazon.awssdk.services.sns.model.PublishBatchRequestEntry;
import software.amazon.awssdk.services.sns.model.PublishRequest;

public class SnsInterceptor implements ExecutionInterceptor {

  public static final ExecutionAttribute<AgentSpan> SPAN_ATTRIBUTE =
      InstanceStore.of(ExecutionAttribute.class)
          .putIfAbsent("DatadogSpan", () -> new ExecutionAttribute<>("DatadogSpan"));

  private SdkBytes getMessageAttributeValueToInject(
      ExecutionAttributes executionAttributes, String snsTopicName) {
    final AgentSpan span = executionAttributes.getAttribute(SPAN_ATTRIBUTE);
    StringBuilder jsonBuilder = new StringBuilder();
    jsonBuilder.append("{");
    propagate().inject(span, jsonBuilder, SETTER, TracePropagationStyle.DATADOG);
    if (traceConfig().isDataStreamsEnabled()) {
      propagate().injectPathwayContext(span, jsonBuilder, SETTER, getTags(snsTopicName));
    }
    jsonBuilder.setLength(jsonBuilder.length() - 1); // Remove the last comma
    jsonBuilder.append("}");
    return SdkBytes.fromString(jsonBuilder.toString(), StandardCharsets.UTF_8);
  }

  public SnsInterceptor() {}

  @Override
  public SdkRequest modifyRequest(
      Context.ModifyRequest context, ExecutionAttributes executionAttributes) {
    // Injecting the trace context into SNS messageAttributes.
    if (context.request() instanceof PublishRequest) {
      PublishRequest request = (PublishRequest) context.request();
      // 10 messageAttributes is a limit from SQS, which is often used as a subscriber, therefore
      // the limit still applies here
      if (request.messageAttributes().size() < 10) {
        // Get topic name for DSM
        String snsTopicArn = request.topicArn() == null ? request.targetArn() : request.topicArn();
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

  private LinkedHashMap<String, String> getTags(String snsTopicName) {
    LinkedHashMap<String, String> sortedTags = new LinkedHashMap<>();
    sortedTags.put(DIRECTION_TAG, DIRECTION_OUT);
    sortedTags.put(TOPIC_TAG, snsTopicName);
    sortedTags.put(TYPE_TAG, "sns");

    return sortedTags;
  }
}

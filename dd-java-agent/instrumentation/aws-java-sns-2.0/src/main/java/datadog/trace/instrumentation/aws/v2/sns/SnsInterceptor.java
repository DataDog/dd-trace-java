package datadog.trace.instrumentation.aws.v2.sns;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.propagate;
import static datadog.trace.instrumentation.aws.v2.sns.TextMapInjectAdapter.SETTER;

import datadog.trace.api.TracePropagationStyle;
import datadog.trace.bootstrap.InstanceStore;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
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

  public SnsInterceptor() {}

  @Override
  public SdkRequest modifyRequest(
      Context.ModifyRequest context, ExecutionAttributes executionAttributes) {
    if (context.request() instanceof PublishRequest) {
      PublishRequest request = (PublishRequest) context.request();
      Map<String, MessageAttributeValue> messageAttributes =
          new HashMap<>(request.messageAttributes());
      final AgentSpan span = executionAttributes.getAttribute(SPAN_ATTRIBUTE);
      // 10 messageAttributes is a limit from SQS, which is often used as a subscriber, therefore
      // the limit still applies here
      if (messageAttributes.size() < 10) {
        StringBuilder jsonBuilder = new StringBuilder();
        jsonBuilder.append("{");
        propagate().inject(span, jsonBuilder, SETTER, TracePropagationStyle.DATADOG);
        jsonBuilder.setLength(jsonBuilder.length() - 1); // Remove the last comma
        jsonBuilder.append("}");

        messageAttributes.put(
            "_datadog", // Use Binary since SNS subscription filter policies fail silently with JSON
            // strings https://github.com/DataDog/datadog-lambda-js/pull/269
            MessageAttributeValue.builder()
                .dataType("Binary")
                .binaryValue(SdkBytes.fromString(jsonBuilder.toString(), StandardCharsets.UTF_8))
                .build());
      }
      return request.toBuilder().messageAttributes(messageAttributes).build();
    } else if (context.request() instanceof PublishBatchRequest) {
      PublishBatchRequest request = (PublishBatchRequest) context.request();
      final AgentSpan span = executionAttributes.getAttribute(SPAN_ATTRIBUTE);
      ArrayList<PublishBatchRequestEntry> entries = new ArrayList<>();
      StringBuilder jsonBuilder = new StringBuilder();
      jsonBuilder.append("{");
      propagate().inject(span, jsonBuilder, SETTER, TracePropagationStyle.DATADOG);
      jsonBuilder.setLength(jsonBuilder.length() - 1); // Remove the last comma
      jsonBuilder.append("}");
      SdkBytes binaryValue = SdkBytes.fromString(jsonBuilder.toString(), StandardCharsets.UTF_8);
      for (PublishBatchRequestEntry entry : request.publishBatchRequestEntries()) {
        Map<String, MessageAttributeValue> messageAttributes =
            new HashMap<>(entry.messageAttributes());
        messageAttributes.put(
            "_datadog",
            MessageAttributeValue.builder().dataType("Binary").binaryValue(binaryValue).build());
        entries.add(entry.toBuilder().messageAttributes(messageAttributes).build());
      }
      return request.toBuilder().publishBatchRequestEntries(entries).build();
    }
    return context.request();
  }
}

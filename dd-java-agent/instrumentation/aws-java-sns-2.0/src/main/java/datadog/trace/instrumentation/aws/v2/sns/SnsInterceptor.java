package datadog.trace.instrumentation.aws.v2.sns;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.propagate;
import static datadog.trace.instrumentation.aws.v2.sns.MessageAttributeInjector.SETTER;

import datadog.trace.api.TracePropagationStyle;
import datadog.trace.bootstrap.InstanceStore;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
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
      propagate().inject(span, messageAttributes, SETTER, TracePropagationStyle.XRAY);
      return request.toBuilder().messageAttributes(messageAttributes).build();
    } else if (context.request() instanceof PublishBatchRequest) {
      PublishBatchRequest request = (PublishBatchRequest) context.request();
      final AgentSpan span = executionAttributes.getAttribute(SPAN_ATTRIBUTE);
      ArrayList<PublishBatchRequestEntry> entries = new ArrayList<>();
      for (PublishBatchRequestEntry entry : request.publishBatchRequestEntries()) {
        Map<String, MessageAttributeValue> messageAttributes =
            new HashMap<>(entry.messageAttributes());
        propagate().inject(span, messageAttributes, SETTER, TracePropagationStyle.XRAY);
        entries.add(entry.toBuilder().messageAttributes(messageAttributes).build());
      }
      return request.toBuilder().publishBatchRequestEntries(entries).build();
    }
    return context.request();
  }
}

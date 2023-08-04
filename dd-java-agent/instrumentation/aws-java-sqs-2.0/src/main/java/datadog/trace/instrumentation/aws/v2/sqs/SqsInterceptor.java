package datadog.trace.instrumentation.aws.v2.sqs;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.propagate;
import static datadog.trace.bootstrap.instrumentation.api.PathwayContext.DATADOG_KEY;
import static datadog.trace.bootstrap.instrumentation.api.URIUtils.urlFileName;
import static datadog.trace.core.datastreams.TagsProcessor.DIRECTION_OUT;
import static datadog.trace.core.datastreams.TagsProcessor.DIRECTION_TAG;
import static datadog.trace.core.datastreams.TagsProcessor.TOPIC_TAG;
import static datadog.trace.core.datastreams.TagsProcessor.TYPE_TAG;
import static datadog.trace.instrumentation.aws.v2.sqs.MessageAttributeInjector.SETTER;

import datadog.trace.bootstrap.InstanceStore;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import software.amazon.awssdk.core.SdkRequest;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttribute;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequestEntry;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

public class SqsInterceptor implements ExecutionInterceptor {

  public static final ExecutionAttribute<AgentSpan> SPAN_ATTRIBUTE =
      InstanceStore.of(ExecutionAttribute.class)
          .putIfAbsent("DatadogSpan", () -> new ExecutionAttribute<>("DatadogSpan"));

  public SqsInterceptor() {}

  @Override
  public SdkRequest modifyRequest(
      Context.ModifyRequest context, ExecutionAttributes executionAttributes) {
    if (context.request() instanceof SendMessageRequest) {
      SendMessageRequest request = (SendMessageRequest) context.request();
      Optional<String> optionalQueueUrl = request.getValueForField("QueueUrl", String.class);
      if (!optionalQueueUrl.isPresent()) {
        return request;
      }
      String queueUrl = optionalQueueUrl.get();
      AgentSpan span = executionAttributes.getAttribute(SPAN_ATTRIBUTE);
      LinkedHashMap<String, String> sortedTags = new LinkedHashMap<>();
      sortedTags.put(DIRECTION_TAG, DIRECTION_OUT);
      sortedTags.put(TOPIC_TAG, urlFileName(queueUrl));
      sortedTags.put(TYPE_TAG, "sqs");

      Map<String, MessageAttributeValue> messageAttributes =
          new HashMap<>(request.messageAttributes());
      propagate().injectPathwayContext(span, messageAttributes, SETTER, sortedTags);
      return request.toBuilder().messageAttributes(messageAttributes).build();

    } else if (context.request() instanceof SendMessageBatchRequest) {
      SendMessageBatchRequest request = (SendMessageBatchRequest) context.request();
      Optional<String> optionalQueueUrl = request.getValueForField("QueueUrl", String.class);
      if (!optionalQueueUrl.isPresent()) {
        return request;
      }
      String queueUrl = optionalQueueUrl.get();
      AgentSpan span = executionAttributes.getAttribute(SPAN_ATTRIBUTE);
      LinkedHashMap<String, String> sortedTags = new LinkedHashMap<>();
      sortedTags.put(DIRECTION_TAG, DIRECTION_OUT);
      sortedTags.put(TOPIC_TAG, urlFileName(queueUrl));
      sortedTags.put(TYPE_TAG, "sqs");

      List<SendMessageBatchRequestEntry> entries = new ArrayList<>();

      for (SendMessageBatchRequestEntry entry : request.entries()) {
        Map<String, MessageAttributeValue> messageAttributes =
            new HashMap<>(entry.messageAttributes());
        propagate().injectPathwayContext(span, messageAttributes, SETTER, sortedTags);
        entries.add(entry.toBuilder().messageAttributes(messageAttributes).build());
      }

      return request.toBuilder().entries(entries).build();

    } else if (context.request() instanceof ReceiveMessageRequest) {
      ReceiveMessageRequest request = (ReceiveMessageRequest) context.request();
      if (request.messageAttributeNames().size() < 10
          && !request.messageAttributeNames().contains(DATADOG_KEY)) {
        List<String> messageAttributeNames = new ArrayList<>(request.messageAttributeNames());
        messageAttributeNames.add(DATADOG_KEY);
        return request.toBuilder().messageAttributeNames(messageAttributeNames).build();
      } else {
        return request;
      }
    } else {
      return context.request();
    }
  }
}

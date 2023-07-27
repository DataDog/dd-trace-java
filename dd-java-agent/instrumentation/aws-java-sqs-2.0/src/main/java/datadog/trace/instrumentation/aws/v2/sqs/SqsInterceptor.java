package datadog.trace.instrumentation.aws.v2.sqs;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.propagate;
import static datadog.trace.bootstrap.instrumentation.api.PathwayContext.DSM_KEY;
import static datadog.trace.bootstrap.instrumentation.api.PathwayContext.PROPAGATION_KEY_BASE64;
import static datadog.trace.bootstrap.instrumentation.api.URIUtils.parseSqsUrl;
import static datadog.trace.core.datastreams.TagsProcessor.DIRECTION_OUT;
import static datadog.trace.core.datastreams.TagsProcessor.DIRECTION_TAG;
import static datadog.trace.core.datastreams.TagsProcessor.TOPIC_TAG;
import static datadog.trace.core.datastreams.TagsProcessor.TYPE_TAG;
import static datadog.trace.instrumentation.aws.v2.AwsExecutionAttribute.SPAN_ATTRIBUTE;

import datadog.trace.api.Config;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import software.amazon.awssdk.core.SdkRequest;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequestEntry;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

public class SqsInterceptor implements ExecutionInterceptor {

  private final ContextStore<Object, String> responseQueueStore;

  public SqsInterceptor(ContextStore<Object, String> responseQueueStore) {
    this.responseQueueStore = responseQueueStore;
  }

  @Override
  public SdkRequest modifyRequest(
      Context.ModifyRequest context, ExecutionAttributes executionAttributes) {
    if (Config.get().isDataStreamsEnabled()) {
      if (context.request() instanceof SendMessageRequest) {
        SendMessageRequest request = (SendMessageRequest) context.request();
        String queueUrl = request.getValueForField("QueueUrl", String.class).get().toString();
        AgentSpan span = executionAttributes.getAttribute(SPAN_ATTRIBUTE);
        LinkedHashMap<String, String> sortedTags = new LinkedHashMap<>();
        sortedTags.put(DIRECTION_TAG, DIRECTION_OUT);
        sortedTags.put(TOPIC_TAG, parseSqsUrl(queueUrl));
        sortedTags.put(TYPE_TAG, "sqs");

        String pathway = propagate().generatePathwayContext(span, sortedTags);

        String jsonPathway = String.format("{\"%s\": \"%s\"}", PROPAGATION_KEY_BASE64, pathway);
        Map<String, MessageAttributeValue> messageAttributes =
            new HashMap<>(request.messageAttributes());

        if (messageAttributes.size() < 10 && !messageAttributes.containsKey(DSM_KEY)) {
          messageAttributes.put(
              DSM_KEY,
              MessageAttributeValue.builder().dataType("String").stringValue(jsonPathway).build());
          return request.toBuilder().messageAttributes(messageAttributes).build();
        } else {
          return request;
        }

      } else if (context.request() instanceof SendMessageBatchRequest) {
        SendMessageBatchRequest request = (SendMessageBatchRequest) context.request();
        String queueUrl = request.getValueForField("QueueUrl", String.class).get().toString();
        AgentSpan span = executionAttributes.getAttribute(SPAN_ATTRIBUTE);
        LinkedHashMap<String, String> sortedTags = new LinkedHashMap<>();
        sortedTags.put(DIRECTION_TAG, DIRECTION_OUT);
        sortedTags.put(TOPIC_TAG, parseSqsUrl(queueUrl));
        sortedTags.put(TYPE_TAG, "sqs");

        List<SendMessageBatchRequestEntry> entries = new ArrayList<>();
        String jsonPathway = "";
        for (SendMessageBatchRequestEntry entry : request.entries()) {
          String pathway = propagate().generatePathwayContext(span, sortedTags);
          Map<String, MessageAttributeValue> messageAttributes =
              new HashMap<>(entry.messageAttributes());
          jsonPathway = String.format("{\"%s\": \"%s\"}", PROPAGATION_KEY_BASE64, pathway);

          if (messageAttributes.size() < 10 && !messageAttributes.containsKey(DSM_KEY)) {
            messageAttributes.put(
                DSM_KEY,
                MessageAttributeValue.builder()
                    .dataType("String")
                    .stringValue(jsonPathway)
                    .build());
            entries.add(entry.toBuilder().messageAttributes(messageAttributes).build());
          } else {
            entries.add(entry.toBuilder().build());
          }
        }

        return request.toBuilder().entries(entries).build();

      } else if (context.request() instanceof ReceiveMessageRequest) {
        ReceiveMessageRequest request = (ReceiveMessageRequest) context.request();
        List<String> messageAttributeNames = new ArrayList<>(request.messageAttributeNames());
        if (messageAttributeNames.size() < 10 && !messageAttributeNames.contains(DSM_KEY)) {
          messageAttributeNames.add(DSM_KEY);
          return request.toBuilder().messageAttributeNames(messageAttributeNames).build();
        } else {
          return request;
        }
      } else {
        return context.request();
      }
    } else {
      return context.request();
    }
  }
}

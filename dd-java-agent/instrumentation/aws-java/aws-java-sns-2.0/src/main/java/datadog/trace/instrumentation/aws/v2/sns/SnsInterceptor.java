package datadog.trace.instrumentation.aws.v2.sns;

import static datadog.context.propagation.Propagators.defaultPropagator;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.traceConfig;

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

  // SQS subscriber limit; SNS inherits it when SQS is used as a subscriber
  private static final int MAX_MESSAGE_ATTRIBUTES = 10;

  public static final ExecutionAttribute<Context> CONTEXT_ATTRIBUTE =
      InstanceStore.of(ExecutionAttribute.class)
          .putIfAbsent("DatadogContext", () -> new ExecutionAttribute<>("DatadogContext"));

  private SdkBytes getMessageAttributeValueToInject(
      ExecutionAttributes executionAttributes, String snsTopicName) {
    Context context = executionAttributes.getAttribute(CONTEXT_ATTRIBUTE);
    StringBuilder jsonBuilder = new StringBuilder();
    jsonBuilder.append('{');
    if (traceConfig().isDataStreamsEnabled()) {
      DataStreamsTags tags =
          DataStreamsTags.create("sns", DataStreamsTags.Direction.OUTBOUND, snsTopicName);
      DataStreamsContext dsmContext = DataStreamsContext.fromTags(tags);
      context = context.with(dsmContext);
    }
    defaultPropagator().inject(context, jsonBuilder, TextMapInjectAdapter.SETTER);
    jsonBuilder.setLength(jsonBuilder.length() - 1); // Remove the last comma
    jsonBuilder.append('}');
    return SdkBytes.fromString(jsonBuilder.toString(), StandardCharsets.UTF_8);
  }

  public SnsInterceptor() {}

  @Override
  public SdkRequest modifyRequest(ModifyRequest context, ExecutionAttributes executionAttributes) {
    if (!Config.get().isSnsInjectDatadogAttributeEnabled()) {
      return context.request();
    }
    // Injecting the trace context into SNS messageAttributes.
    if (context.request() instanceof PublishRequest) {
      PublishRequest request = (PublishRequest) context.request();
      if (request.messageAttributes().size() < MAX_MESSAGE_ATTRIBUTES) {
        // Get topic name for DSM
        String snsTopicArn = request.topicArn();
        if (null == snsTopicArn) {
          snsTopicArn = request.targetArn();
          if (null == snsTopicArn) {
            return request; // request is to phone number, ignore for DSM
          }
        }

        String snsTopicName = snsTopicArn.substring(snsTopicArn.lastIndexOf(':') + 1);
        Map<String, MessageAttributeValue> messageAttributes =
            withDatadogAttribute(
                request.messageAttributes(),
                this.getMessageAttributeValueToInject(executionAttributes, snsTopicName));
        return request.toBuilder().messageAttributes(messageAttributes).build();
      }
      return request;
    } else if (context.request() instanceof PublishBatchRequest) {
      PublishBatchRequest request = (PublishBatchRequest) context.request();
      // Get topic name for DSM
      String snsTopicArn = request.topicArn();
      String snsTopicName = snsTopicArn.substring(snsTopicArn.lastIndexOf(':') + 1);
      ArrayList<PublishBatchRequestEntry> entries = new ArrayList<>();
      SdkBytes value = this.getMessageAttributeValueToInject(executionAttributes, snsTopicName);
      for (PublishBatchRequestEntry entry : request.publishBatchRequestEntries()) {
        if (entry.messageAttributes().size() < MAX_MESSAGE_ATTRIBUTES) {
          Map<String, MessageAttributeValue> messageAttributes =
              withDatadogAttribute(entry.messageAttributes(), value);
          entry = entry.toBuilder().messageAttributes(messageAttributes).build();
        }
        entries.add(entry);
      }
      return request.toBuilder().publishBatchRequestEntries(entries).build();
    }
    return context.request();
  }

  private static Map<String, MessageAttributeValue> withDatadogAttribute(
      Map<String, MessageAttributeValue> attributes, SdkBytes value) {
    // copy since the original map may be unmodifiable
    Map<String, MessageAttributeValue> modified = new HashMap<>(attributes);
    // Use Binary since SNS subscription filter policies fail silently with JSON strings
    // https://github.com/DataDog/datadog-lambda-js/pull/269
    modified.put(
        "_datadog", MessageAttributeValue.builder().dataType("Binary").binaryValue(value).build());
    return modified;
  }
}

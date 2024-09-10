package datadog.trace.instrumentation.aws.v2.eventbridge;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.propagate;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.traceConfig;
import static datadog.trace.core.datastreams.TagsProcessor.BUS_TAG;
import static datadog.trace.core.datastreams.TagsProcessor.DIRECTION_OUT;
import static datadog.trace.core.datastreams.TagsProcessor.DIRECTION_TAG;
import static datadog.trace.core.datastreams.TagsProcessor.TYPE_TAG;
import static datadog.trace.instrumentation.aws.v2.eventbridge.TextMapInjectAdapter.SETTER;

import datadog.trace.api.TracePropagationStyle;
import datadog.trace.bootstrap.InstanceStore;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.*;
import software.amazon.awssdk.core.SdkRequest;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttribute;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequest;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequestEntry;

public class EventBridgeInterceptor implements ExecutionInterceptor {
  public static final ExecutionAttribute<AgentSpan> SPAN_ATTRIBUTE =
      InstanceStore.of(ExecutionAttribute.class)
          .putIfAbsent("DatadogSpan", () -> new ExecutionAttribute<>("DatadogSpan"));

  private String getTraceContextToInject(
      ExecutionAttributes executionAttributes, String eventBusName) {
    final AgentSpan span = executionAttributes.getAttribute(SPAN_ATTRIBUTE);
    StringBuilder jsonBuilder = new StringBuilder();
    jsonBuilder.append("{");
    propagate().inject(span, jsonBuilder, SETTER, TracePropagationStyle.DATADOG);
    if (traceConfig().isDataStreamsEnabled()) {
      propagate().injectPathwayContext(span, jsonBuilder, SETTER, getTags(eventBusName));
    }
    jsonBuilder.setLength(jsonBuilder.length() - 1); // Remove the last comma
    jsonBuilder.append("}");
    return jsonBuilder.toString();
  }

  @Override
  public SdkRequest modifyRequest(
      Context.ModifyRequest context, ExecutionAttributes executionAttributes) {
    // Injecting the trace context into EventBridge `detail`
    if (context.request() instanceof PutEventsRequest) {
      PutEventsRequest request = (PutEventsRequest) context.request();
      List<PutEventsRequestEntry> modifiedEntries = new ArrayList<>();
      long startTime = System.currentTimeMillis();

      for (PutEventsRequestEntry entry : request.entries()) {
        String eventBusName = entry.eventBusName();
        String traceContext = getTraceContextToInject(executionAttributes, eventBusName);

        StringBuilder detailBuilder = new StringBuilder(entry.detail());
        detailBuilder.setLength(detailBuilder.length() - 1); // Remove the last bracket
        detailBuilder
            .append(", \"SentTimestamp\": \"")
            .append(startTime)
            .append(
                "\""); // add start trace timestamp, since AWS's current timestamp only has second
        // resolution
        detailBuilder
            .append(", \"BusName\": \"")
            .append(eventBusName)
            .append("\""); // add bus name, since AWS currently doesn't include this in the payload
        detailBuilder
            .append(", \"_datadog\": ")
            .append(traceContext)
            .append("}"); // add trace context
        String modifiedDetail = detailBuilder.toString();

        PutEventsRequestEntry modifiedEntry = entry.toBuilder().detail(modifiedDetail).build();
        modifiedEntries.add(modifiedEntry);

        // TODO SQS limit of 10 messageAttributes?
      }

      return request.toBuilder().entries(modifiedEntries).build();
    }

    return context.request();
  }

  private LinkedHashMap<String, String> getTags(String eventBusName) {
    LinkedHashMap<String, String> sortedTags = new LinkedHashMap<>();
    sortedTags.put(DIRECTION_TAG, DIRECTION_OUT);
    sortedTags.put(BUS_TAG, eventBusName);
    sortedTags.put(TYPE_TAG, "bus");

    return sortedTags;
  }
}

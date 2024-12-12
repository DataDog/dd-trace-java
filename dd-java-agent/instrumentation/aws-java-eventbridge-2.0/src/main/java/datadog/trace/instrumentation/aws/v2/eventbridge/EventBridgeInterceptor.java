package datadog.trace.instrumentation.aws.v2.eventbridge;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.propagate;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.traceConfig;
import static datadog.trace.core.datastreams.TagsProcessor.BUS_TAG;
import static datadog.trace.core.datastreams.TagsProcessor.DIRECTION_OUT;
import static datadog.trace.core.datastreams.TagsProcessor.DIRECTION_TAG;
import static datadog.trace.core.datastreams.TagsProcessor.TYPE_TAG;
import static datadog.trace.instrumentation.aws.v2.eventbridge.TextMapInjectAdapter.SETTER;

import datadog.trace.bootstrap.InstanceStore;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.PathwayContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.SdkRequest;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttribute;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequest;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequestEntry;

public class EventBridgeInterceptor implements ExecutionInterceptor {
  private static final Logger log = LoggerFactory.getLogger(EventBridgeInterceptor.class);

  public static final ExecutionAttribute<AgentSpan> SPAN_ATTRIBUTE =
      InstanceStore.of(ExecutionAttribute.class)
          .putIfAbsent("DatadogSpan", () -> new ExecutionAttribute<>("DatadogSpan"));

  private static final String START_TIME_KEY = "x-datadog-start-time";
  private static final String RESOURCE_NAME_KEY = "x-datadog-resource-name";

  @Override
  public SdkRequest modifyRequest(
      Context.ModifyRequest context, ExecutionAttributes executionAttributes) {
    if (!(context.request() instanceof PutEventsRequest)) {
      return context.request();
    }

    PutEventsRequest request = (PutEventsRequest) context.request();
    List<PutEventsRequestEntry> modifiedEntries = new ArrayList<>(request.entries().size());
    long startTime = System.currentTimeMillis();

    for (PutEventsRequestEntry entry : request.entries()) {
      StringBuilder detailBuilder = new StringBuilder(entry.detail().trim());
      if (detailBuilder.length() == 0) {
        detailBuilder.append("{}");
      }
      if (detailBuilder.charAt(detailBuilder.length() - 1) != '}') {
        log.debug(
            "Unable to parse detail JSON. Not injecting trace context into EventBridge payload.");
        modifiedEntries.add(entry); // Add the original entry without modification
        continue;
      }

      String traceContext =
          getTraceContextToInject(executionAttributes, entry.eventBusName(), startTime);
      detailBuilder.setLength(detailBuilder.length() - 1); // Remove the last bracket
      if (detailBuilder.length() > 1) {
        detailBuilder.append(", "); // Only add a comma if detail is not empty.
      }

      detailBuilder
          .append('\"')
          .append(PathwayContext.DATADOG_KEY)
          .append("\": ")
          .append(traceContext)
          .append('}');

      String modifiedDetail = detailBuilder.toString();
      PutEventsRequestEntry modifiedEntry = entry.toBuilder().detail(modifiedDetail).build();
      modifiedEntries.add(modifiedEntry);
    }

    return request.toBuilder().entries(modifiedEntries).build();
  }

  private String getTraceContextToInject(
      ExecutionAttributes executionAttributes, String eventBusName, long startTime) {
    final AgentSpan span = executionAttributes.getAttribute(SPAN_ATTRIBUTE);
    StringBuilder jsonBuilder = new StringBuilder();
    jsonBuilder.append('{');

    // Inject trace context
    propagate().inject(span, jsonBuilder, SETTER);

    if (traceConfig().isDataStreamsEnabled()) {
      propagate().injectPathwayContext(span, jsonBuilder, SETTER, getTags(eventBusName));
    }

    // Add bus name and start time
    jsonBuilder
        .append(" \"")
        .append(START_TIME_KEY)
        .append("\": \"")
        .append(startTime)
        .append("\", ");
    jsonBuilder
        .append(" \"")
        .append(RESOURCE_NAME_KEY)
        .append("\": \"")
        .append(eventBusName)
        .append('\"');

    jsonBuilder.append('}');
    return jsonBuilder.toString();
  }

  private LinkedHashMap<String, String> getTags(String eventBusName) {
    LinkedHashMap<String, String> sortedTags = new LinkedHashMap<>();
    sortedTags.put(DIRECTION_TAG, DIRECTION_OUT);
    sortedTags.put(BUS_TAG, eventBusName);
    sortedTags.put(TYPE_TAG, "bus");

    return sortedTags;
  }
}

package datadog.trace.instrumentation.aws.v2.eventbridge;

import static datadog.context.propagation.Propagators.defaultPropagator;
import static datadog.trace.api.datastreams.DataStreamsTags.Direction.OUTBOUND;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.traceConfig;
import static datadog.trace.instrumentation.aws.v2.eventbridge.TextMapInjectAdapter.SETTER;

import datadog.context.Context;
import datadog.trace.api.Config;
import datadog.trace.api.datastreams.DataStreamsContext;
import datadog.trace.api.datastreams.DataStreamsTags;
import datadog.trace.api.datastreams.PathwayContext;
import datadog.trace.bootstrap.InstanceStore;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.SdkRequest;
import software.amazon.awssdk.core.interceptor.Context.ModifyRequest;
import software.amazon.awssdk.core.interceptor.ExecutionAttribute;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequest;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequestEntry;

public class EventBridgeInterceptor implements ExecutionInterceptor {
  private static final Logger log = LoggerFactory.getLogger(EventBridgeInterceptor.class);

  public static final ExecutionAttribute<Context> CONTEXT_ATTRIBUTE =
      InstanceStore.of(ExecutionAttribute.class)
          .putIfAbsent("DatadogContext", () -> new ExecutionAttribute<>("DatadogContext"));

  private static final String START_TIME_KEY = "x-datadog-start-time";
  private static final String RESOURCE_NAME_KEY = "x-datadog-resource-name";

  @Override
  public SdkRequest modifyRequest(ModifyRequest context, ExecutionAttributes executionAttributes) {
    if (!(context.request() instanceof PutEventsRequest)
        || !Config.get().isAwsInjectDatadogAttributeEnabled()) {
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
    Context context = executionAttributes.getAttribute(CONTEXT_ATTRIBUTE);
    StringBuilder jsonBuilder = new StringBuilder();
    jsonBuilder.append('{');

    // Inject context
    if (traceConfig().isDataStreamsEnabled()) {
      DataStreamsTags tags = DataStreamsTags.createWithBus(OUTBOUND, eventBusName);
      DataStreamsContext dsmContext = DataStreamsContext.fromTags(tags);
      context = context.with(dsmContext);
    }
    defaultPropagator().inject(context, jsonBuilder, SETTER);

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
}

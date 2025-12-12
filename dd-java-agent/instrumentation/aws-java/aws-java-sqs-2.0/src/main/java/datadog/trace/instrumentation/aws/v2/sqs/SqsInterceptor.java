package datadog.trace.instrumentation.aws.v2.sqs;

import static datadog.trace.api.datastreams.DataStreamsTags.Direction.OUTBOUND;
import static datadog.trace.api.datastreams.DataStreamsTags.create;
import static datadog.trace.api.datastreams.PathwayContext.DATADOG_KEY;
import static datadog.trace.bootstrap.instrumentation.api.AgentPropagation.DSM_CONCERN;
import static datadog.trace.bootstrap.instrumentation.api.URIUtils.urlFileName;
import static datadog.trace.instrumentation.aws.v2.sqs.MessageAttributeInjector.SETTER;

import datadog.context.Context;
import datadog.context.propagation.Propagator;
import datadog.context.propagation.Propagators;
import datadog.trace.api.Config;
import datadog.trace.api.datastreams.DataStreamsContext;
import datadog.trace.api.datastreams.DataStreamsTags;
import datadog.trace.bootstrap.InstanceStore;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import software.amazon.awssdk.core.SdkRequest;
import software.amazon.awssdk.core.interceptor.Context.ModifyRequest;
import software.amazon.awssdk.core.interceptor.ExecutionAttribute;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequestEntry;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

public class SqsInterceptor implements ExecutionInterceptor {

  public static final ExecutionAttribute<Context> CONTEXT_ATTRIBUTE =
      InstanceStore.of(ExecutionAttribute.class)
          .putIfAbsent("DatadogContext", () -> new ExecutionAttribute<>("DatadogContext"));

  public SqsInterceptor() {}

  @Override
  public SdkRequest modifyRequest(ModifyRequest context, ExecutionAttributes executionAttributes) {
    if (context.request() instanceof SendMessageRequest) {
      SendMessageRequest request = (SendMessageRequest) context.request();
      Optional<String> optionalQueueUrl = request.getValueForField("QueueUrl", String.class);
      if (!optionalQueueUrl.isPresent()) {
        return request;
      }

      Propagator dsmPropagator = Propagators.forConcern(DSM_CONCERN);
      Context ctx = getContext(executionAttributes, optionalQueueUrl.get());
      Map<String, MessageAttributeValue> messageAttributes =
          new HashMap<>(request.messageAttributes());
      dsmPropagator.inject(ctx, messageAttributes, SETTER);

      return request.toBuilder().messageAttributes(messageAttributes).build();

    } else if (context.request() instanceof SendMessageBatchRequest) {
      SendMessageBatchRequest request = (SendMessageBatchRequest) context.request();
      Optional<String> optionalQueueUrl = request.getValueForField("QueueUrl", String.class);
      if (!optionalQueueUrl.isPresent()) {
        return request;
      }

      Propagator dsmPropagator = Propagators.forConcern(DSM_CONCERN);
      Context ctx = getContext(executionAttributes, optionalQueueUrl.get());
      List<SendMessageBatchRequestEntry> entries = new ArrayList<>();

      for (SendMessageBatchRequestEntry entry : request.entries()) {
        Map<String, MessageAttributeValue> messageAttributes =
            new HashMap<>(entry.messageAttributes());
        dsmPropagator.inject(ctx, messageAttributes, SETTER);
        entries.add(entry.toBuilder().messageAttributes(messageAttributes).build());
      }

      return request.toBuilder().entries(entries).build();

    } else if (context.request() instanceof ReceiveMessageRequest) {
      ReceiveMessageRequest request = (ReceiveMessageRequest) context.request();
      if (request.messageAttributeNames().size() < 10
          && !request.messageAttributeNames().contains(DATADOG_KEY)
          && Config.get().isSqsInjectDatadogAttributeEnabled()) {
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

  private Context getContext(ExecutionAttributes executionAttributes, String queueUrl) {
    Context context = executionAttributes.getAttribute(CONTEXT_ATTRIBUTE);

    DataStreamsTags tags = create("sqs", OUTBOUND, urlFileName(queueUrl));
    DataStreamsContext dsmContext = DataStreamsContext.fromTags(tags);
    return context.with(dsmContext);
  }
}

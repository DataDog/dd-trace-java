package datadog.trace.instrumentation.aws.v1.sns;

import static datadog.context.propagation.Propagators.defaultPropagator;
import static datadog.trace.api.datastreams.DataStreamsTags.Direction.OUTBOUND;
import static datadog.trace.api.datastreams.DataStreamsTags.create;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.traceConfig;
import static datadog.trace.instrumentation.aws.v1.sns.TextMapInjectAdapter.SETTER;

import com.amazonaws.AmazonWebServiceRequest;
import com.amazonaws.handlers.RequestHandler2;
import com.amazonaws.services.sns.model.MessageAttributeValue;
import com.amazonaws.services.sns.model.PublishBatchRequest;
import com.amazonaws.services.sns.model.PublishBatchRequestEntry;
import com.amazonaws.services.sns.model.PublishRequest;
import datadog.context.Context;
import datadog.trace.api.Config;
import datadog.trace.api.datastreams.DataStreamsContext;
import datadog.trace.api.datastreams.DataStreamsTags;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class SnsInterceptor extends RequestHandler2 {

  private final ContextStore<AmazonWebServiceRequest, Context> contextStore;

  public SnsInterceptor(ContextStore<AmazonWebServiceRequest, Context> contextStore) {
    this.contextStore = contextStore;
  }

  private ByteBuffer getMessageAttributeValueToInject(
      AmazonWebServiceRequest request, String snsTopicName) {
    final AgentSpan span = newSpan(request);
    StringBuilder jsonBuilder = new StringBuilder();
    jsonBuilder.append('{');
    Context context = span;
    if (traceConfig().isDataStreamsEnabled()) {
      DataStreamsContext dsmContext = DataStreamsContext.fromTags(getTags(snsTopicName));
      context = context.with(dsmContext);
    }
    defaultPropagator().inject(context, jsonBuilder, SETTER);
    jsonBuilder.setLength(jsonBuilder.length() - 1); // Remove the last comma
    jsonBuilder.append('}');
    return ByteBuffer.wrap(jsonBuilder.toString().getBytes(StandardCharsets.UTF_8));
  }

  @Override
  public AmazonWebServiceRequest beforeMarshalling(AmazonWebServiceRequest request) {
    if (!Config.get().isSnsInjectDatadogAttributeEnabled()) {
      return request;
    }
    // Injecting the trace context into SNS messageAttributes.
    if (request instanceof PublishRequest) {
      PublishRequest pRequest = (PublishRequest) request;
      // note: modifying message attributes has to be done before marshalling, otherwise the changes
      // are not reflected in the actual request (and the MD5 check on send will fail).
      Map<String, MessageAttributeValue> messageAttributes = pRequest.getMessageAttributes();
      // 10 messageAttributes is a limit from SQS, which is often used as a subscriber, therefore
      // the limit still applies here
      if (messageAttributes.size() < 10) {
        // Extract the topic name from the ARN for DSM
        String topicName = pRequest.getTopicArn();
        if (null == topicName) {
          topicName = pRequest.getTargetArn();
          if (null == topicName) {
            return request; // request is to phone number, ignore for DSM
          }
        }

        topicName = topicName.substring(topicName.lastIndexOf(':') + 1);

        HashMap<String, MessageAttributeValue> modifiedMessageAttributes =
            new HashMap<>(messageAttributes);
        modifiedMessageAttributes.put(
            "_datadog",
            new MessageAttributeValue()
                .withDataType(
                    "Binary") // Use Binary since SNS subscription filter policies fail silently
                // with JSON strings
                // https://github.com/DataDog/datadog-lambda-js/pull/269
                .withBinaryValue(this.getMessageAttributeValueToInject(request, topicName)));

        pRequest.setMessageAttributes(modifiedMessageAttributes);
      }
    } else if (request instanceof PublishBatchRequest) {
      PublishBatchRequest pmbRequest = (PublishBatchRequest) request;
      // Extract the topic name from the ARN for DSM
      String topicName = pmbRequest.getTopicArn();
      topicName = topicName.substring(topicName.lastIndexOf(':') + 1);

      final ByteBuffer bytebuffer = this.getMessageAttributeValueToInject(request, topicName);
      for (PublishBatchRequestEntry entry : pmbRequest.getPublishBatchRequestEntries()) {
        Map<String, MessageAttributeValue> messageAttributes = entry.getMessageAttributes();
        if (messageAttributes.size() < 10) {
          HashMap<String, MessageAttributeValue> modifiedMessageAttributes =
              new HashMap<>(messageAttributes);
          modifiedMessageAttributes.put(
              "_datadog",
              new MessageAttributeValue().withDataType("Binary").withBinaryValue(bytebuffer));
          entry.setMessageAttributes(modifiedMessageAttributes);
        }
      }
    }
    return request;
  }

  private AgentSpan newSpan(AmazonWebServiceRequest request) {
    final AgentSpan span = AgentTracer.startSpan("aws.sns.send");
    // pass the span to TracingRequestHandler in the sdk instrumentation where it'll be enriched &
    // activated
    // TODO If DSM is enabled, add DSM context here too
    contextStore.put(request, span);
    return span;
  }

  private DataStreamsTags getTags(String snsTopicName) {
    return create("sns", OUTBOUND, snsTopicName);
  }
}

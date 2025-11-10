package datadog.trace.instrumentation.aws.v1.sqs;

import static datadog.trace.api.datastreams.DataStreamsTags.Direction.OUTBOUND;
import static datadog.trace.api.datastreams.DataStreamsTags.create;
import static datadog.trace.api.datastreams.PathwayContext.DATADOG_KEY;
import static datadog.trace.bootstrap.instrumentation.api.AgentPropagation.DSM_CONCERN;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.bootstrap.instrumentation.api.URIUtils.urlFileName;
import static datadog.trace.instrumentation.aws.v1.sqs.MessageAttributeInjector.SETTER;

import com.amazonaws.AmazonWebServiceRequest;
import com.amazonaws.handlers.RequestHandler2;
import com.amazonaws.services.sqs.model.MessageAttributeValue;
import com.amazonaws.services.sqs.model.ReceiveMessageRequest;
import com.amazonaws.services.sqs.model.SendMessageBatchRequest;
import com.amazonaws.services.sqs.model.SendMessageBatchRequestEntry;
import com.amazonaws.services.sqs.model.SendMessageRequest;
import datadog.context.Context;
import datadog.context.propagation.Propagator;
import datadog.context.propagation.Propagators;
import datadog.trace.api.Config;
import datadog.trace.api.datastreams.DataStreamsContext;
import datadog.trace.api.datastreams.DataStreamsTags;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SqsInterceptor extends RequestHandler2 {

  private final ContextStore<AmazonWebServiceRequest, Context> contextStore;

  public SqsInterceptor(ContextStore<AmazonWebServiceRequest, Context> contextStore) {
    this.contextStore = contextStore;
  }

  @Override
  public AmazonWebServiceRequest beforeMarshalling(AmazonWebServiceRequest request) {
    if (request instanceof SendMessageRequest) {
      SendMessageRequest smRequest = (SendMessageRequest) request;

      String queueUrl = smRequest.getQueueUrl();
      if (queueUrl == null) return request;

      Propagator dsmPropagator = Propagators.forConcern(DSM_CONCERN);
      Context context = newContext(request, queueUrl);
      // making a copy of the MessageAttributes before modifying them because they can be stored in
      // a kind of ImmutableMap
      Map<String, MessageAttributeValue> messageAttributes =
          new HashMap<>(smRequest.getMessageAttributes());
      dsmPropagator.inject(context, messageAttributes, SETTER);
      // note: modifying message attributes has to be done before marshalling, otherwise the changes
      // are not reflected in the actual request (and the MD5 check on send will fail).
      smRequest.setMessageAttributes(messageAttributes);
    } else if (request instanceof SendMessageBatchRequest) {
      SendMessageBatchRequest smbRequest = (SendMessageBatchRequest) request;

      String queueUrl = smbRequest.getQueueUrl();
      if (queueUrl == null) return request;

      Propagator dsmPropagator = Propagators.forConcern(DSM_CONCERN);
      Context context = newContext(request, queueUrl);
      for (SendMessageBatchRequestEntry entry : smbRequest.getEntries()) {
        Map<String, MessageAttributeValue> messageAttributes =
            new HashMap<>(entry.getMessageAttributes());
        dsmPropagator.inject(context, messageAttributes, SETTER);
        entry.setMessageAttributes(messageAttributes);
      }
    } else if (request instanceof ReceiveMessageRequest) {
      ReceiveMessageRequest rmRequest = (ReceiveMessageRequest) request;
      if (Config.get().isAwsInjectDatadogAttributeEnabled()
          && rmRequest.getMessageAttributeNames().size() < 10
          && !rmRequest.getMessageAttributeNames().contains(DATADOG_KEY)) {
        List<String> attributeNames = new ArrayList<>(rmRequest.getMessageAttributeNames());
        attributeNames.add(DATADOG_KEY);
        rmRequest.setMessageAttributeNames(attributeNames);
      }
    }
    return request;
  }

  private Context newContext(AmazonWebServiceRequest request, String queueUrl) {
    AgentSpan span = newSpan(request);
    DataStreamsContext dsmContext = DataStreamsContext.fromTags(getTags(queueUrl));
    return span.with(dsmContext);
  }

  private AgentSpan newSpan(AmazonWebServiceRequest request) {
    final AgentSpan span = startSpan("sqs", "aws.sqs.send");
    // pass the span to TracingRequestHandler in the sdk instrumentation where it'll be enriched &
    // activated
    // TODO If DSM is enabled, add DSM context here too
    contextStore.put(request, span);
    return span;
  }

  private static DataStreamsTags getTags(String queueUrl) {
    return create("sqs", OUTBOUND, urlFileName(queueUrl));
  }
}

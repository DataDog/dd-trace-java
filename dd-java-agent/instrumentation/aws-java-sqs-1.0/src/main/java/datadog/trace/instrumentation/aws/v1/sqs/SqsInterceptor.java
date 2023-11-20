package datadog.trace.instrumentation.aws.v1.sqs;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.propagate;
import static datadog.trace.bootstrap.instrumentation.api.PathwayContext.DATADOG_KEY;
import static datadog.trace.bootstrap.instrumentation.api.URIUtils.urlFileName;
import static datadog.trace.core.datastreams.TagsProcessor.DIRECTION_OUT;
import static datadog.trace.core.datastreams.TagsProcessor.DIRECTION_TAG;
import static datadog.trace.core.datastreams.TagsProcessor.TOPIC_TAG;
import static datadog.trace.core.datastreams.TagsProcessor.TYPE_TAG;
import static datadog.trace.instrumentation.aws.v1.sqs.MessageAttributeInjector.SETTER;

import com.amazonaws.AmazonWebServiceRequest;
import com.amazonaws.handlers.HandlerContextKey;
import com.amazonaws.handlers.RequestHandler2;
import com.amazonaws.services.sqs.model.MessageAttributeValue;
import com.amazonaws.services.sqs.model.ReceiveMessageRequest;
import com.amazonaws.services.sqs.model.SendMessageBatchRequest;
import com.amazonaws.services.sqs.model.SendMessageBatchRequestEntry;
import com.amazonaws.services.sqs.model.SendMessageRequest;
import datadog.trace.bootstrap.FieldBackedContextStores;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.LinkedHashMap;
import java.util.Map;

public class SqsInterceptor extends RequestHandler2 {

  public static final HandlerContextKey<AgentSpan> SPAN_CONTEXT_KEY =
      new HandlerContextKey<>("DatadogSpan");

  public SqsInterceptor() {}

  @Override
  public AmazonWebServiceRequest beforeMarshalling(AmazonWebServiceRequest request) {
    if (request instanceof SendMessageRequest) {
      SendMessageRequest smRequest = (SendMessageRequest) request;

      String queueUrl = smRequest.getQueueUrl();
      if (queueUrl == null) return request;

      LinkedHashMap<String, String> sortedTags = new LinkedHashMap<>();
      sortedTags.put(DIRECTION_TAG, DIRECTION_OUT);
      sortedTags.put(TOPIC_TAG, urlFileName(queueUrl));
      sortedTags.put(TYPE_TAG, "sqs");

      // retrieve the span that was created in
      // datadog.trace.instrumentation.aws.v0.TracingRequestHandler.beforeMarshalling
      final AgentSpan span =
          (AgentSpan)
              FieldBackedContextStores.getContextStore(
                      FieldBackedContextStores.getContextStoreId(
                          AmazonWebServiceRequest.class.getName(), AgentSpan.class.getName()))
                  .get(request);
      // note: modifying message attributes has to be done before marshalling, otherwise the changes
      // are not reflected in the actual request (and the MD5 check on send will fail).
      Map<String, MessageAttributeValue> messageAttributes = smRequest.getMessageAttributes();
      propagate().injectPathwayContext(span, messageAttributes, SETTER, sortedTags);
      smRequest.setMessageAttributes(messageAttributes);
    } else if (request instanceof SendMessageBatchRequest) {
      SendMessageBatchRequest smbRequest = (SendMessageBatchRequest) request;

      String queueUrl = smbRequest.getQueueUrl();
      if (queueUrl == null) return request;

      LinkedHashMap<String, String> sortedTags = new LinkedHashMap<>();
      sortedTags.put(DIRECTION_TAG, DIRECTION_OUT);
      sortedTags.put(TOPIC_TAG, urlFileName(queueUrl));
      sortedTags.put(TYPE_TAG, "sqs");

      final AgentSpan span =
          (AgentSpan)
              FieldBackedContextStores.getContextStore(
                      FieldBackedContextStores.getContextStoreId(
                          AmazonWebServiceRequest.class.getName(), AgentSpan.class.getName()))
                  .get(request);
      for (SendMessageBatchRequestEntry entry : smbRequest.getEntries()) {
        Map<String, MessageAttributeValue> messageAttributes = entry.getMessageAttributes();
        propagate().injectPathwayContext(span, messageAttributes, SETTER, sortedTags);
        entry.setMessageAttributes(messageAttributes);
      }
    } else if (request instanceof ReceiveMessageRequest) {
      ReceiveMessageRequest rmRequest = (ReceiveMessageRequest) request;
      if (rmRequest.getMessageAttributeNames().size() < 10
          && !rmRequest.getMessageAttributeNames().contains(DATADOG_KEY)) {
        rmRequest.getMessageAttributeNames().add(DATADOG_KEY);
      }
    }
    return request;
  }
}

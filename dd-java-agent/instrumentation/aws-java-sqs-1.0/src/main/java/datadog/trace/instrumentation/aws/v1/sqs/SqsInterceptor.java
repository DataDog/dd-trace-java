package datadog.trace.instrumentation.aws.v1.sqs;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.propagate;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.bootstrap.instrumentation.api.PathwayContext.DATADOG_KEY;
import static datadog.trace.bootstrap.instrumentation.api.URIUtils.urlFileName;
import static datadog.trace.core.datastreams.TagsProcessor.DIRECTION_OUT;
import static datadog.trace.core.datastreams.TagsProcessor.DIRECTION_TAG;
import static datadog.trace.core.datastreams.TagsProcessor.TOPIC_TAG;
import static datadog.trace.core.datastreams.TagsProcessor.TYPE_TAG;
import static datadog.trace.instrumentation.aws.v1.sqs.MessageAttributeInjector.SETTER;

import com.amazonaws.AmazonWebServiceRequest;
import com.amazonaws.handlers.RequestHandler2;
import com.amazonaws.services.sqs.model.ReceiveMessageRequest;
import com.amazonaws.services.sqs.model.SendMessageBatchRequest;
import com.amazonaws.services.sqs.model.SendMessageBatchRequestEntry;
import com.amazonaws.services.sqs.model.SendMessageRequest;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.LinkedHashMap;

public class SqsInterceptor extends RequestHandler2 {

  private final ContextStore<AmazonWebServiceRequest, AgentSpan> contextStore;

  public SqsInterceptor(ContextStore<AmazonWebServiceRequest, AgentSpan> contextStore) {
    this.contextStore = contextStore;
  }

  @Override
  public AmazonWebServiceRequest beforeMarshalling(AmazonWebServiceRequest request) {
    if (request instanceof SendMessageRequest) {
      SendMessageRequest smRequest = (SendMessageRequest) request;

      String queueUrl = smRequest.getQueueUrl();
      if (queueUrl == null) return request;

      LinkedHashMap<String, String> sortedTags = getTags(queueUrl);

      final AgentSpan span = newSpan(request);
      // note: modifying message attributes has to be done before marshalling, otherwise the changes
      // are not reflected in the actual request (and the MD5 check on send will fail).
      propagate().injectPathwayContext(span, smRequest.getMessageAttributes(), SETTER, sortedTags);
    } else if (request instanceof SendMessageBatchRequest) {
      SendMessageBatchRequest smbRequest = (SendMessageBatchRequest) request;

      String queueUrl = smbRequest.getQueueUrl();
      if (queueUrl == null) return request;

      LinkedHashMap<String, String> sortedTags = getTags(queueUrl);

      final AgentSpan span = newSpan(request);
      for (SendMessageBatchRequestEntry entry : smbRequest.getEntries()) {
        propagate().injectPathwayContext(span, entry.getMessageAttributes(), SETTER, sortedTags);
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

  private AgentSpan newSpan(AmazonWebServiceRequest request) {
    final AgentSpan span = startSpan("aws.sqs.send");
    // pass the span to TracingRequestHandler in the sdk instrumentation where it'll be enriched &
    // activated
    contextStore.put(request, span);
    return span;
  }

  private static LinkedHashMap<String, String> getTags(String queueUrl) {
    LinkedHashMap<String, String> sortedTags = new LinkedHashMap<>();
    sortedTags.put(DIRECTION_TAG, DIRECTION_OUT);
    sortedTags.put(TOPIC_TAG, urlFileName(queueUrl));
    sortedTags.put(TYPE_TAG, "sqs");
    return sortedTags;
  }
}

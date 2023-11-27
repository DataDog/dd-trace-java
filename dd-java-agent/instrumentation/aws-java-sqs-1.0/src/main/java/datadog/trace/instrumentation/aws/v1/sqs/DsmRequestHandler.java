package datadog.trace.instrumentation.aws.v1.sqs;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.propagate;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.core.datastreams.TagsProcessor.DIRECTION_OUT;
import static datadog.trace.core.datastreams.TagsProcessor.DIRECTION_TAG;
import static datadog.trace.core.datastreams.TagsProcessor.TOPIC_TAG;
import static datadog.trace.core.datastreams.TagsProcessor.TYPE_TAG;
import static datadog.trace.instrumentation.aws.v1.sqs.MessageAttributeInjector.SETTER;

import com.amazonaws.AmazonWebServiceRequest;
import com.amazonaws.Request;
import com.amazonaws.Response;
import com.amazonaws.handlers.HandlerContextKey;
import com.amazonaws.handlers.RequestHandler2;
import com.amazonaws.services.sqs.model.MessageAttributeValue;
import com.amazonaws.services.sqs.model.SendMessageBatchRequest;
import com.amazonaws.services.sqs.model.SendMessageBatchRequestEntry;
import com.amazonaws.services.sqs.model.SendMessageRequest;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.URIUtils;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Tracing Request Handler */
public class DsmRequestHandler extends RequestHandler2 {
  public static final HandlerContextKey<AgentSpan> SPAN_CONTEXT_KEY =
      new HandlerContextKey<>("DatadogSpan"); // same as OnErrorDecorator.SPAN_CONTEXT_KEY

  private static final Logger log = LoggerFactory.getLogger(DsmRequestHandler.class);

  private final ContextStore<AmazonWebServiceRequest, AgentSpan> requestSpanStore;

  public DsmRequestHandler(ContextStore<AmazonWebServiceRequest, AgentSpan> requestSpanStore) {
    this.requestSpanStore = requestSpanStore;
  }

  @Override
  public AmazonWebServiceRequest beforeMarshalling(final AmazonWebServiceRequest request) {
    if (!Config.get().isDataStreamsEnabled()) {
      return request;
    }
    boolean requestMatches = false;
    boolean isBatch = false;
    String urlQueue = null;

    // seems that those names are only used for sqs
    if (request instanceof SendMessageRequest) {
      requestMatches = true;
      urlQueue = URIUtils.urlFileName(((SendMessageRequest) request).getQueueUrl());

    } else if (request instanceof SendMessageBatchRequest) {
      requestMatches = true;
      isBatch = true;
      urlQueue = URIUtils.urlFileName(((SendMessageBatchRequest) request).getQueueUrl());
    }
    if (requestMatches) {
      // let's early create a span
      AgentSpan span = startSpan("aws.request"); // will be changed beforeRequest
      LinkedHashMap<String, String> sortedTags = new LinkedHashMap<>(3);
      sortedTags.put(DIRECTION_TAG, DIRECTION_OUT);
      sortedTags.put(TOPIC_TAG, urlQueue);
      sortedTags.put(TYPE_TAG, "sqs");

      if (isBatch) {
        for (SendMessageBatchRequestEntry entry :
            ((SendMessageBatchRequest) request).getEntries()) {
          Map<String, MessageAttributeValue> messageAttributes = entry.getMessageAttributes();
          propagate().injectPathwayContext(span, messageAttributes, SETTER, sortedTags);
          entry.setMessageAttributes(messageAttributes);
        }
      } else {
        Map<String, MessageAttributeValue> messageAttributes =
            ((SendMessageRequest) request).getMessageAttributes();
        propagate().injectPathwayContext(span, messageAttributes, SETTER, sortedTags);
        ((SendMessageRequest) request).setMessageAttributes(messageAttributes);
      }
      requestSpanStore.put(request, span);
    }
    return request;
  }

  @Override
  public void beforeRequest(final Request<?> request) {}

  @Override
  public void afterResponse(final Request<?> request, final Response<?> response) {}

  @Override
  public void afterError(final Request<?> request, final Response<?> response, final Exception e) {}
}

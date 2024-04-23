package datadog.trace.instrumentation.aws.v1.sns;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.propagate;
import static datadog.trace.instrumentation.aws.v1.sns.MessageAttributeInjector.SETTER;

import com.amazonaws.AmazonWebServiceRequest;
import com.amazonaws.handlers.RequestHandler2;
import com.amazonaws.services.sns.model.PublishBatchRequest;
import com.amazonaws.services.sns.model.PublishBatchRequestEntry;
import com.amazonaws.services.sns.model.PublishRequest;
import datadog.trace.api.TracePropagationStyle;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;

public class SnsInterceptor extends RequestHandler2 {

  private final ContextStore<AmazonWebServiceRequest, AgentSpan> contextStore;

  public SnsInterceptor(ContextStore<AmazonWebServiceRequest, AgentSpan> contextStore) {
    this.contextStore = contextStore;
  }

  @Override
  public AmazonWebServiceRequest beforeMarshalling(AmazonWebServiceRequest request) {
    // Injecting the AWSTraceHeader into SNS messageAttributes. This is consistent with SQS cases.
    if (request instanceof PublishRequest) {
      PublishRequest pRequest = (PublishRequest) request;
      final AgentSpan span = newSpan(request);
      // note: modifying message attributes has to be done before marshalling, otherwise the changes
      // are not reflected in the actual request (and the MD5 check on send will fail).
      propagate().inject(span, pRequest.getMessageAttributes(), SETTER, TracePropagationStyle.XRAY);

    } else if (request instanceof PublishBatchRequest) {
      PublishBatchRequest pmbRequest = (PublishBatchRequest) request;

      final AgentSpan span = newSpan(request);
      for (PublishBatchRequestEntry entry : pmbRequest.getPublishBatchRequestEntries()) {
        propagate().inject(span, entry.getMessageAttributes(), SETTER, TracePropagationStyle.XRAY);
      }
    }
    return request;
  }

  private AgentSpan newSpan(AmazonWebServiceRequest request) {
    final AgentSpan span = AgentTracer.startSpan("aws.sns.send");
    // pass the span to TracingRequestHandler in the sdk instrumentation where it'll be enriched &
    // activated
    contextStore.put(request, span);
    return span;
  }
}

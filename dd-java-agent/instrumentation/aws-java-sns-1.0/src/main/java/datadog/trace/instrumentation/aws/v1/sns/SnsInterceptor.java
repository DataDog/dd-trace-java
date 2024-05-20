package datadog.trace.instrumentation.aws.v1.sns;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.propagate;
import static datadog.trace.instrumentation.aws.v1.sns.TextMapInjectAdapter.SETTER;

import com.amazonaws.AmazonWebServiceRequest;
import com.amazonaws.handlers.RequestHandler2;
import com.amazonaws.services.sns.model.MessageAttributeValue;
import com.amazonaws.services.sns.model.PublishBatchRequest;
import com.amazonaws.services.sns.model.PublishBatchRequestEntry;
import com.amazonaws.services.sns.model.PublishRequest;
import datadog.trace.api.TracePropagationStyle;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public class SnsInterceptor extends RequestHandler2 {

  private final ContextStore<AmazonWebServiceRequest, AgentSpan> contextStore;

  public SnsInterceptor(ContextStore<AmazonWebServiceRequest, AgentSpan> contextStore) {
    this.contextStore = contextStore;
  }

  @Override
  public AmazonWebServiceRequest beforeMarshalling(AmazonWebServiceRequest request) {
    // Injecting the trace context into SNS messageAttributes.
    if (request instanceof PublishRequest) {
      PublishRequest pRequest = (PublishRequest) request;
      final AgentSpan span = newSpan(request);
      // note: modifying message attributes has to be done before marshalling, otherwise the changes
      // are not reflected in the actual request (and the MD5 check on send will fail).
      Map<String, MessageAttributeValue> messageAttributes = pRequest.getMessageAttributes();
      // 10 messageAttributes is a limit from SQS, which is often used as a subscriber, therefore
      // the limit still applies here
      if (messageAttributes.size() < 10) {
        StringBuilder jsonBuilder = new StringBuilder();
        jsonBuilder.append("{");
        propagate().inject(span, jsonBuilder, SETTER, TracePropagationStyle.DATADOG);
        jsonBuilder.setLength(jsonBuilder.length() - 1); // Remove the last comma
        jsonBuilder.append("}");
        messageAttributes.put(
            "_datadog",
            new MessageAttributeValue()
                .withDataType(
                    "Binary") // Use Binary since SNS subscription filter policies fail silently
                // with JSON strings
                // https://github.com/DataDog/datadog-lambda-js/pull/269
                .withBinaryValue(
                    ByteBuffer.wrap(jsonBuilder.toString().getBytes(StandardCharsets.UTF_8))));
      }
    } else if (request instanceof PublishBatchRequest) {
      PublishBatchRequest pmbRequest = (PublishBatchRequest) request;

      final AgentSpan span = newSpan(request);
      StringBuilder jsonBuilder = new StringBuilder();
      jsonBuilder.append("{");
      propagate().inject(span, jsonBuilder, SETTER, TracePropagationStyle.DATADOG);
      jsonBuilder.setLength(jsonBuilder.length() - 1); // Remove the last comma
      jsonBuilder.append("}");
      ByteBuffer binaryValue =
          ByteBuffer.wrap(jsonBuilder.toString().getBytes(StandardCharsets.UTF_8));
      for (PublishBatchRequestEntry entry : pmbRequest.getPublishBatchRequestEntries()) {
        Map<String, MessageAttributeValue> messageAttributes = entry.getMessageAttributes();
        if (messageAttributes.size() < 10) {
          messageAttributes.put(
              "_datadog",
              new MessageAttributeValue().withDataType("Binary").withBinaryValue(binaryValue));
        }
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

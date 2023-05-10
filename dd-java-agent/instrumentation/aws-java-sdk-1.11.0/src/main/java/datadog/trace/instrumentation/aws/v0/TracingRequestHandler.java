package datadog.trace.instrumentation.aws.v0;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.noopSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.propagate;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.aws.v0.AwsSdkClientDecorator.AWS_LEGACY_TRACING;
import static datadog.trace.instrumentation.aws.v0.AwsSdkClientDecorator.DECORATE;

import com.amazonaws.AmazonWebServiceRequest;
import com.amazonaws.Request;
import com.amazonaws.Response;
import com.amazonaws.handlers.HandlerContextKey;
import com.amazonaws.handlers.RequestHandler2;
import datadog.trace.api.Config;
import datadog.trace.api.TracePropagationStyle;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Tracing Request Handler */
public class TracingRequestHandler extends RequestHandler2 {

  public static final HandlerContextKey<AgentSpan> SPAN_CONTEXT_KEY =
      new HandlerContextKey<>("DatadogSpan"); // same as OnErrorDecorator.SPAN_CONTEXT_KEY

  private static final Logger log = LoggerFactory.getLogger(TracingRequestHandler.class);

  private final ContextStore<Object, String> responseQueueStore;

  public TracingRequestHandler(ContextStore<Object, String> responseQueueStore) {
    this.responseQueueStore = responseQueueStore;
  }

  @Override
  public AmazonWebServiceRequest beforeMarshalling(final AmazonWebServiceRequest request) {
    return request;
  }

  @Override
  public void beforeRequest(final Request<?> request) {
    final AgentSpan span;
    if (!AWS_LEGACY_TRACING && isPollingRequest(request.getOriginalRequest())) {
      // SQS messages spans are created by aws-java-sqs-1.0 - replace client scope with no-op,
      // so we can tell when receive call is complete without affecting the rest of the trace
      span = noopSpan();
    } else {
      span = startSpan(AwsNameCache.spanName(request));
      DECORATE.afterStart(span);
      DECORATE.onRequest(span, request);
      request.addHandlerContext(SPAN_CONTEXT_KEY, span);
      if (Config.get().isAwsPropagationEnabled()) {
        try {
          propagate().inject(span, request, DECORATE, TracePropagationStyle.XRAY);
        } catch (Throwable e) {
          log.warn("Unable to inject trace header", e);
        }
      }
    }

    // This scope will be closed by AwsHttpClientInstrumentation
    activateSpan(span);
  }

  @Override
  public void afterResponse(final Request<?> request, final Response<?> response) {
    final AgentSpan span = request.getHandlerContext(SPAN_CONTEXT_KEY);
    if (span != null) {
      request.addHandlerContext(SPAN_CONTEXT_KEY, null);
      DECORATE.onResponse(span, response);
      DECORATE.beforeFinish(span);
      span.finish();
    }
    if (!AWS_LEGACY_TRACING && isPollingResponse(response.getAwsResponse())) {
      try {
        // store queueUrl inside response for SqsReceiveResultInstrumentation
        AmazonWebServiceRequest originalRequest = request.getOriginalRequest();
        responseQueueStore.put(
            response.getAwsResponse(),
            RequestAccess.of(originalRequest).getQueueUrl(originalRequest));
      } catch (Throwable e) {
        log.debug("Unable to extract queueUrl from ReceiveMessageRequest", e);
      }
    }
  }

  @Override
  public void afterError(final Request<?> request, final Response<?> response, final Exception e) {
    final AgentSpan span = request.getHandlerContext(SPAN_CONTEXT_KEY);
    if (span != null) {
      request.addHandlerContext(SPAN_CONTEXT_KEY, null);
      DECORATE.onError(span, e);
      DECORATE.beforeFinish(span);
      span.finish();
    }
  }

  private static boolean isPollingRequest(AmazonWebServiceRequest request) {
    return null != request
        && "com.amazonaws.services.sqs.model.ReceiveMessageRequest"
            .equals(request.getClass().getName());
  }

  private static boolean isPollingResponse(Object response) {
    return null != response
        && "com.amazonaws.services.sqs.model.ReceiveMessageResult"
            .equals(response.getClass().getName());
  }
}

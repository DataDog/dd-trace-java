package datadog.trace.instrumentation.aws.v0;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateNext;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.closePrevious;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.aws.v0.AwsSdkClientDecorator.DECORATE;

import com.amazonaws.AmazonWebServiceRequest;
import com.amazonaws.Request;
import com.amazonaws.Response;
import com.amazonaws.handlers.HandlerContextKey;
import com.amazonaws.handlers.RequestHandler2;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;

/** Tracing Request Handler */
public class TracingRequestHandler extends RequestHandler2 {

  // aws1.x sdk doesn't have any truly async clients so we can store scope in request context safely
  public static final HandlerContextKey<AgentScope> SCOPE_CONTEXT_KEY =
      new HandlerContextKey<>("DatadogScope"); // same as OnErrorDecorator.SCOPE_CONTEXT_KEY

  private static final CharSequence AWS_HTTP = UTF8BytesString.create("aws.http");

  @Override
  public AmazonWebServiceRequest beforeMarshalling(final AmazonWebServiceRequest request) {
    return request;
  }

  @Override
  public void beforeRequest(final Request<?> request) {
    boolean isPolling = isPollingRequest(request.getOriginalRequest());
    if (isPolling) {
      closePrevious(true);
    }
    final AgentSpan span = startSpan(AWS_HTTP);
    DECORATE.afterStart(span);
    DECORATE.onRequest(span, request);
    if (isPolling) {
      activateNext(span); // this scope will last until next poll
    }
    request.addHandlerContext(SCOPE_CONTEXT_KEY, activateSpan(span));
  }

  @Override
  public void afterResponse(final Request<?> request, final Response<?> response) {
    final AgentScope scope = request.getHandlerContext(SCOPE_CONTEXT_KEY);
    if (scope != null) {
      request.addHandlerContext(SCOPE_CONTEXT_KEY, null);
      DECORATE.onResponse(scope.span(), response);
      DECORATE.beforeFinish(scope.span());
      scope.close();
      if (isPollingRequest(request.getOriginalRequest())) {
        // will be finished on next poll
      } else {
        scope.span().finish();
      }
    }
  }

  @Override
  public void afterError(final Request<?> request, final Response<?> response, final Exception e) {
    final AgentScope scope = request.getHandlerContext(SCOPE_CONTEXT_KEY);
    if (scope != null) {
      request.addHandlerContext(SCOPE_CONTEXT_KEY, null);
      DECORATE.onError(scope.span(), e);
      DECORATE.beforeFinish(scope.span());
      scope.close();
      if (isPollingRequest(request.getOriginalRequest())) {
        // will be finished on next poll
      } else {
        scope.span().finish();
      }
    }
  }

  private static boolean isPollingRequest(AmazonWebServiceRequest request) {
    return null != request
        && "com.amazonaws.services.sqs.model.ReceiveMessageRequest"
            .equals(request.getClass().getName());
  }
}

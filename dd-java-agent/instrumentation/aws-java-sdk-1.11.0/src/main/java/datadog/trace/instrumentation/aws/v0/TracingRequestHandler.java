package datadog.trace.instrumentation.aws.v0;

import static datadog.trace.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.instrumentation.api.AgentTracer.startSpan;

import com.amazonaws.AmazonWebServiceRequest;
import com.amazonaws.Request;
import com.amazonaws.Response;
import com.amazonaws.handlers.HandlerContextKey;
import com.amazonaws.handlers.RequestHandler2;
import datadog.trace.instrumentation.api.AgentScope;
import datadog.trace.instrumentation.api.AgentSpan;

/** Tracing Request Handler */
public class TracingRequestHandler extends RequestHandler2 {
  public static final TracingRequestHandler INSTANCE = new TracingRequestHandler();

  private final AwsSdkClientDecorator decorator;

  private TracingRequestHandler() {
    this(AwsSdkClientDecorator.DECORATE);
  }

  TracingRequestHandler(final AwsSdkClientDecorator decorator) {
    this.decorator = decorator;
  }

  // Note: aws1.x sdk doesn't have any truly async clients so we can store scope in request context
  // safely.
  public static final HandlerContextKey<AgentScope> SCOPE_CONTEXT_KEY =
      new HandlerContextKey<>("DatadogScope");

  @Override
  public AmazonWebServiceRequest beforeMarshalling(final AmazonWebServiceRequest request) {
    return request;
  }

  @Override
  public void beforeRequest(final Request<?> request) {
    final AgentSpan span = startSpan("aws.command");
    decorator.afterStart(span);
    decorator.onRequest(span, request);
    request.addHandlerContext(SCOPE_CONTEXT_KEY, activateSpan(span, true));
  }

  @Override
  public void afterResponse(final Request<?> request, final Response<?> response) {
    final AgentScope scope = request.getHandlerContext(SCOPE_CONTEXT_KEY);
    if (scope != null) {
      request.addHandlerContext(SCOPE_CONTEXT_KEY, null);
      decorator.onResponse(scope.span(), response);
      decorator.beforeFinish(scope.span());
      scope.close();
    }
  }

  @Override
  public void afterError(final Request<?> request, final Response<?> response, final Exception e) {
    final AgentScope scope = request.getHandlerContext(SCOPE_CONTEXT_KEY);
    if (scope != null) {
      request.addHandlerContext(SCOPE_CONTEXT_KEY, null);
      decorator.onError(scope.span(), e);
      decorator.beforeFinish(scope.span());
      scope.close();
    }
  }
}

package datadog.trace.instrumentation.servlet.http.common;

import static datadog.trace.api.gateway.Events.REQUEST_BODY_START;

import datadog.trace.api.function.BiFunction;
import datadog.trace.api.gateway.CallbackProvider;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.http.StoredBodySupplier;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator;
import javax.servlet.http.HttpServletRequest;

public abstract class ServletHttpServerDecorator<RESPONSE>
    extends HttpServerDecorator<HttpServletRequest, HttpServletRequest, RESPONSE> {

  private volatile Boolean hasIGCallback;

  @Override
  public HttpServletRequest wrapRequest(AgentSpan span, HttpServletRequest req) {
    if (hasIGCallback == null) {
      BiFunction<RequestContext, StoredBodySupplier, Void> callback =
          AgentTracer.get().instrumentationGateway().getCallback(REQUEST_BODY_START);
      hasIGCallback = callback != null;
    }

    if (!hasIGCallback) {
      return req;
    }

    if (req instanceof StoredBodySupplier) {
      return req;
    }

    RequestContext requestContext = span.getRequestContext();
    if (requestContext == null) {
      return req;
    }

    CallbackProvider cbp = AgentTracer.get().instrumentationGateway();
    return new BodyCapturingHttpServletRequest(
        req, new IGDelegatingStoredBodyListener(cbp, requestContext));
  }
}

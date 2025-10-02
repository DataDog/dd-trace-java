package datadog.trace.instrumentation.servlet2;

import static datadog.trace.instrumentation.servlet2.HttpServletRequestExtractAdapter.GETTER;

import datadog.context.Context;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapter;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator;
import javax.servlet.http.HttpServletRequest;

public class Servlet2Decorator
    extends HttpServerDecorator<
        HttpServletRequest, HttpServletRequest, Integer, HttpServletRequest> {
  public static final CharSequence JAVA_WEB_SERVLET = UTF8BytesString.create("java-web-servlet");
  public static final Servlet2Decorator DECORATE = new Servlet2Decorator();
  public static final CharSequence SERVLET_REQUEST =
      UTF8BytesString.create(DECORATE.operationName());

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"servlet", "servlet-2"};
  }

  @Override
  protected CharSequence component() {
    return JAVA_WEB_SERVLET;
  }

  @Override
  protected String requestedSessionId(final HttpServletRequest request) {
    return request.getRequestedSessionId();
  }

  @Override
  protected AgentPropagation.ContextVisitor<HttpServletRequest> getter() {
    return GETTER;
  }

  @Override
  protected AgentPropagation.ContextVisitor<Integer> responseGetter() {
    return null; // There is no way to access the headers
  }

  @Override
  public CharSequence spanName() {
    return SERVLET_REQUEST;
  }

  @Override
  protected String method(final HttpServletRequest httpServletRequest) {
    return httpServletRequest.getMethod();
  }

  @Override
  protected URIDataAdapter url(final HttpServletRequest httpServletRequest) {
    return new ServletRequestURIAdapter(httpServletRequest);
  }

  @Override
  protected String peerHostIP(final HttpServletRequest httpServletRequest) {
    return httpServletRequest.getRemoteAddr();
  }

  @Override
  protected int peerPort(final HttpServletRequest httpServletRequest) {
    // HttpServletResponse doesn't have accessor for remote port.
    return 0;
  }

  @Override
  protected int status(final Integer status) {
    return status;
  }

  @Override
  public AgentSpan onRequest(
      final AgentSpan span,
      final HttpServletRequest connection,
      final HttpServletRequest request,
      final Context parentContext) {
    assert span != null;
    if (request != null) {
      span.setTag("servlet.context", request.getContextPath());
      span.setTag("servlet.path", request.getServletPath());
    }
    return super.onRequest(span, connection, request, parentContext);
  }
}
